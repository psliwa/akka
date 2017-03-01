/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.persistence.journal.chaos

import java.util.concurrent.ThreadLocalRandom

import akka.persistence._
import akka.persistence.journal.AsyncWriteJournal
import akka.persistence.journal.inmem.InmemMessages

import scala.collection.immutable
import scala.concurrent.Future
import scala.util.Try
import scala.util.control.NonFatal

/**
 * INTERNAL API.
 *
 * Chaos journal for reliability testing purposes only.
 */
private class ChaosJournal extends AsyncWriteJournal {

  val config = context.system.settings.config.getConfig("akka.persistence.journal.chaos")
  val writeFailureRate = config.getDouble("write-failure-rate")
  val deleteFailureRate = config.getDouble("delete-failure-rate")
  val replayFailureRate = config.getDouble("replay-failure-rate")
  val readHighestFailureRate = config.getDouble("read-highest-failure-rate")
  val surviveRestarts = config.getBoolean("survive-restarts")

  val messages = if (surviveRestarts) ChaosJournalMessages else new InmemMessages() {}

  import messages.{ delete ⇒ del, _ }

  def random = ThreadLocalRandom.current

  override def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]): Future[immutable.Seq[Try[Unit]]] =
    try Future.successful {
      if (shouldFail(writeFailureRate)) throw new WriteFailedException(messages.flatMap(_.payload))
      else
        for (a ← messages) yield {
          a.payload.foreach(add)
          AsyncWriteJournal.successUnit
        }
    } catch {
      case NonFatal(e) ⇒ Future.failed(e)
    }

  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] = {
    try Future.successful {
      (1L to toSequenceNr).foreach { snr ⇒
        del(persistenceId, snr)
      }
    } catch {
      case NonFatal(e) ⇒ Future.failed(e)
    }
  }

  def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(replayCallback: (PersistentRepr) ⇒ Unit): Future[Unit] =
    if (shouldFail(replayFailureRate)) {
      val rm = read(persistenceId, fromSequenceNr, toSequenceNr, max)
      val sm = rm.take(random.nextInt(rm.length + 1))
      sm.foreach(replayCallback)
      Future.failed(new ReplayFailedException(sm))
    } else {
      read(persistenceId, fromSequenceNr, toSequenceNr, max).foreach(replayCallback)
      Future.successful(())
    }

  def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] =
    if (shouldFail(readHighestFailureRate)) Future.failed(new ReadHighestFailedException)
    else Future.successful(highestSequenceNr(persistenceId))

  def shouldFail(rate: Double): Boolean =
    random.nextDouble() < rate
}

private class WriteFailedException(ps: Seq[PersistentRepr])
  extends Exception(s"write failed for payloads = [${ps.map(_.payload)}]")

private class ReplayFailedException(ps: Seq[PersistentRepr])
  extends Exception(s"recovery failed after replaying payloads = [${ps.map(_.payload)}]")

private class ReadHighestFailedException
  extends Exception(s"recovery failed when reading highest sequence number")

/**
 * Keep [[ChaosJournal]] state in an external singleton so that it survives journal restarts.
 * The journal itself uses a dedicated dispatcher, so there won't be any visibility issues.
 */
private object ChaosJournalMessages extends InmemMessages
