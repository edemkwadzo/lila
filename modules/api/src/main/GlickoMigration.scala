package lila.api

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.{ Try, Success, Failure }

import org.goochjs.glicko2._
import org.joda.time.DateTime
import play.api.libs.iteratee._
import play.api.libs.json.Json
import reactivemongo.bson._

import lila.db.api._
import lila.db.Implicits._
import lila.game.Game
import lila.game.Game.{ BSONFields ⇒ G }
import lila.round.PerfsUpdater.{ Ratings, resultOf, updateRatings, mkPerfs, system }
import lila.user.{ User, UserRepo, HistoryRepo, Glicko, GlickoEngine, Perfs, Perf, HistoryEntry }

object GlickoMigration {

  def apply(
    db: lila.db.Env,
    gameEnv: lila.game.Env,
    userEnv: lila.user.Env) = {

    val oldUserColl = db("user3")
    val oldUserRepo = new UserRepo {
      def userTube = lila.user.tube.userTube inColl oldUserColl
    }
    val limit = Int.MaxValue
    // val limit = 300000
    // val limit = 1000
    var nb = 0

    import scala.collection.mutable
    val ratings = mutable.Map.empty[String, Ratings]
    val histories = mutable.Map.empty[String, mutable.ListBuffer[HistoryEntry]]

    val enumerator: Enumerator[Option[Game]] = lila.game.tube.gameTube |> { implicit gameTube ⇒
      import Game.gameBSONHandler
      $query(lila.game.Query.rated)
        // .batch(1000)
        .sort($sort asc G.createdAt)
        .cursor[Option[Game]].enumerate(limit, false)
    }

    def iteratee(isEngine: Set[String]): Iteratee[Option[Game], Unit] = {
      Iteratee.foreach[Option[Game]] {
        _ foreach { game ⇒
          nb = nb + 1
          if (nb % 1000 == 0) println(nb)
          game.userIds match {
            case List(uidW, uidB) if isEngine(uidW) || isEngine(uidB) || (uidW == uidB) ⇒
            case List(uidW, uidB) ⇒ {
              val ratingsW = ratings.getOrElseUpdate(uidW, mkRatings)
              val ratingsB = ratings.getOrElseUpdate(uidB, mkRatings)
              val globalRatingW = ratingsW.global.getRating
              val globalRatingB = ratingsB.global.getRating
              val result = resultOf(game)
              updateRatings(ratingsW.global, ratingsB.global, result, system)
              updateRatings(ratingsW.white, ratingsB.black, result, system)
              game.variant match {
                case chess.Variant.Standard ⇒
                  updateRatings(ratingsW.standard, ratingsB.standard, result, system)
                case chess.Variant.Chess960 ⇒
                  updateRatings(ratingsW.chess960, ratingsB.chess960, result, system)
                case _ ⇒
              }
              chess.Speed(game.clock) match {
                case chess.Speed.Bullet ⇒
                  updateRatings(ratingsW.bullet, ratingsB.bullet, result, system)
                case chess.Speed.Blitz ⇒
                  updateRatings(ratingsW.blitz, ratingsB.blitz, result, system)
                case chess.Speed.Slow | chess.Speed.Unlimited ⇒
                  updateRatings(ratingsW.slow, ratingsB.slow, result, system)
              }
              histories.getOrElseUpdate(uidW, mkHistory) +=
                HistoryEntry(game.createdAt, ratingsW.global.getRating.toInt, ratingsW.global.getRatingDeviation.toInt, globalRatingB.toInt)
              histories.getOrElseUpdate(uidB, mkHistory) +=
                HistoryEntry(game.createdAt, ratingsB.global.getRating.toInt, ratingsB.global.getRatingDeviation.toInt, globalRatingW.toInt)
            }
            case _ ⇒
          }
        }
      }
    }

    def mkHistory = mutable.ListBuffer(
      HistoryEntry(DateTime.now, Glicko.default.intRating, Glicko.default.intDeviation, Glicko.default.intRating)
    )

    def mkRatings = {
      def r = new Rating(system.getDefaultRating, system.getDefaultRatingDeviation, system.getDefaultVolatility, 0)
      new Ratings(r, r, r, r, r, r, r, r)
    }

    def updateUsers(userPerfs: Map[String, Perfs]): Future[Unit] = lila.user.tube.userTube |> { implicit userTube ⇒
      userTube.coll.drop() flatMap { _ ⇒
        oldUserColl.genericQueryBuilder.cursor[BSONDocument].enumerate() |>>> Iteratee.foreach[BSONDocument] { user ⇒
          for {
            id ← user.getAs[String]("_id")
            perfs ← userPerfs get id
          } userTube.coll insert {
            writeDoc(user, Set("elo", "variantElos", "speedElos")) ++ BSONDocument(
              User.BSONFields.perfs -> lila.user.Perfs.tube.handler.write(perfs),
              User.BSONFields.rating -> perfs.global.glicko.intRating
            )
          }
        }
      }
    }

    def updateHistories(histories: Iterable[(String, Iterable[HistoryEntry])]): Funit = {
      userEnv.historyColl.drop() recover {
        case e: Exception ⇒ fuccess()
      } flatMap { _ ⇒
        Future.traverse(histories) {
          case (id, history) ⇒ HistoryRepo.set(id, history)
        }
      }
    }.void

    oldUserRepo.engineIds flatMap { engineIds ⇒
      (enumerator |>>> iteratee(engineIds)) flatMap { _ ⇒
        val perfs = (ratings mapValues mkPerfs).toMap
        updateUsers(perfs) flatMap { _ ⇒
          updateHistories(histories) map { _ ⇒
            println("Done!")
            "done"
          }
        }
      }
    }
  }

  private def writeDoc(doc: BSONDocument, drops: Set[String]) = BSONDocument(doc.elements collect {
    case (k, v) if !drops(k) ⇒ k -> v
  })
}