package lila.lobby

import scala.concurrent.duration._

import akka.actor._
import akka.pattern.{ ask, pipe }

import actorApi._
import lila.db.api._
import lila.game.GameRepo
import lila.hub.actorApi.GetUids
import lila.socket.actorApi.Broom
import makeTimeout.short
import org.joda.time.DateTime

private[lobby] final class Lobby(
    socket: ActorRef,
    seekApi: SeekApi,
    blocking: String => Fu[Set[String]],
    onStart: String => Unit) extends Actor {

  def receive = {

    case GetOpen(userOption) =>
      val replyTo = sender
      (userOption.map(_.id) ?? blocking) foreach { blocks =>
        val lobbyUser = userOption map { LobbyUser.make(_, blocks) }
        replyTo ! HookRepo.list.filter { Biter.canJoin(_, lobbyUser) }
      }

    case msg@AddHook(hook) => {
      HookRepo byUid hook.uid foreach remove
      hook.sid ?? { sid => HookRepo bySid sid foreach remove }
      findCompatible(hook) foreach {
        case Some(h) => self ! BiteHook(h.id, hook.uid, hook.user)
        case None    => self ! SaveHook(msg)
      }
    }

    case msg@AddSeek(seek) =>
      findCompatible(seek) foreach {
        case Some(s) => self ! BiteSeek(s.id, seek.user)
        case None    => self ! SaveSeek(msg)
      }

    case SaveHook(msg) =>
      HookRepo save msg.hook
      socket ! msg

    case SaveSeek(msg) => (seekApi insert msg.seek) >>- {
      socket ! msg
    }

    case CancelHook(uid) => {
      HookRepo byUid uid foreach remove
    }

    case CancelSeek(seekId, user) => seekApi.removeBy(seekId, user.id) >>- {
      socket ! RemoveSeek(seekId)
    }

    case BiteHook(hookId, uid, user) => HookRepo byId hookId foreach { hook =>
      HookRepo byUid uid foreach remove
      Biter(hook, uid, user) pipeTo self
    }

    case BiteSeek(seekId, user) => seekApi find seekId foreach {
      _ foreach { seek =>
        Biter(seek, user) pipeTo self
      }
    }

    case msg@JoinHook(_, hook, game, _) =>
      onStart(game.id)
      socket ! msg
      remove(hook)

    case msg@JoinSeek(_, seek, game, _) =>
      onStart(game.id)
      socket ! msg
      (seekApi remove seek) >>- {
        socket ! RemoveSeek(seek.id)
      }

    case Broom => socket ? GetUids mapTo manifest[Iterable[String]] foreach { uids =>
      val hooks = {
        (HookRepo notInUids uids.toSet) ::: HookRepo.cleanupOld
      }.toSet
      if (hooks.nonEmpty) self ! RemoveHooks(hooks)
    }

    case RemoveHooks(hooks) => hooks foreach remove

    case Resync             => socket ! HookIds(HookRepo.list map (_.id))
  }

  private def findCompatible(hook: Hook): Fu[Option[Hook]] =
    findCompatibleIn(hook, HookRepo findCompatible hook)

  private def findCompatibleIn(hook: Hook, in: List[Hook]): Fu[Option[Hook]] = in match {
    case Nil => fuccess(none)
    case h :: rest => Biter.canJoin(h, hook.user) ?? !{
      (h.user |@| hook.user).tupled ?? {
        case (u1, u2) =>
          GameRepo.lastGameBetween(u1.id, u2.id, DateTime.now minusHours 1) map {
            _ ?? (_.aborted)
          }
      }
    } flatMap {
      case true  => fuccess(h.some)
      case false => findCompatibleIn(hook, rest)
    }
  }

  private def findCompatible(seek: Seek): Fu[Option[Seek]] =
    seekApi forUser seek.user map {
      _ find (_ compatibleWith seek)
    }

  private def remove(hook: Hook) = {
    HookRepo remove hook
    socket ! RemoveHook(hook.id)
  }
}
