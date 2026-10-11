package org.maproulette.evolutions

import java.nio.file.{Files, Path, Paths}
import org.scalatestplus.play.PlaySpec
import scala.jdk.CollectionConverters._

/**
  * Checks conf/evolutions/ledger.txt against conf/evolutions/default/ (docs/upstream-sync.md).
  * Play reads 1.sql, 2.sql, ... and stops at the first missing number, so a gap silently drops
  * every later migration; and an upstream file that merges in at a free number would run without
  * anyone deciding where it goes. No database needed.
  */
object EvolutionLedger {
  sealed trait Owner
  case object Fork               extends Owner
  case class Upstream(file: Int) extends Owner
  case object Reserved           extends Owner
  case class Row(number: Int, owner: Owner)
  case class Ledger(base: Int, synced: Int, rows: Seq[Row])

  private val Numbered = """([1-9][0-9]*)\.sql""".r

  def parse(text: String): Either[String, Ledger] = {
    val lines = text.linesIterator.map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#")).toSeq
    val base  = lines.collectFirst { case l if l.startsWith("base ") => l.split("\\s+")(1).toInt }
    val synced = lines.collectFirst {
      case l if l.startsWith("synced ") => l.split("\\s+")(2).toInt
    }
    val rows = lines.filterNot(l => l.startsWith("base ") || l.startsWith("synced ")).map { line =>
      line.split("\\s+").toList match {
        case n :: "fork" :: _     => Right(Row(n.toInt, Fork))
        case n :: "reserved" :: _ => Right(Row(n.toInt, Reserved))
        case n :: o :: _ if o.matches("upstream:[1-9][0-9]*") =>
          Right(Row(n.toInt, Upstream(o.drop(9).toInt)))
        case _ => Left(s"Unreadable ledger line: $line")
      }
    }
    (base, synced, rows.collectFirst { case Left(e) => e }) match {
      case (_, _, Some(error))   => Left(error)
      case (None, _, _)          => Left("The ledger has no base line")
      case (_, None, _)          => Left("The ledger has no synced line")
      case (Some(b), Some(s), _) => Right(Ledger(b, s, rows.collect { case Right(r) => r }))
    }
  }

  /**
    * Every problem with `ledger` against the evolution files: names (non-numbered file names to
    * their text are reported too) and file texts by number.
    */
  def problems(ledger: Ledger, names: Seq[String], files: Map[Int, String]): Seq[String] = {
    val stray = names
      .filter {
        case Numbered(_) => false
        case _           => true
      }
      .map(n => s"$n is not named N.sql, so Play never reads it")
    val max  = if (files.isEmpty) 0 else files.keys.max
    val gaps = (1 to max).filterNot(files.contains).map(n => s"$n.sql is missing; Play stops there")
    val dupes = ledger.rows.groupBy(_.number).collect {
      case (n, rs) if rs.size > 1 => s"Number $n has ${rs.size} ledger rows"
    }
    val byNumber = ledger.rows.map(r => r.number -> r.owner).toMap
    val belowBase = ledger.rows.filter(_.number <= ledger.base).map { r =>
      s"Row ${r.number} is at or below the upstream base ${ledger.base}"
    }
    val unlisted =
      files.keys.toSeq.sorted.filter(n => n > ledger.base && !byNumber.contains(n)).map { n =>
        s"$n.sql has no ledger row. Claim the number in conf/evolutions/ledger.txt"
      }
    val rowChecks = ledger.rows.sortBy(_.number).flatMap {
      case Row(n, Reserved) if files.contains(n) =>
        Some(s"$n.sql exists but its row says reserved. Change the row to fork")
      case Row(n, Reserved) if n <= max =>
        Some(s"Reserved $n is below the highest file $max.sql; it would leave a gap")
      case Row(n, Fork) if !files.contains(n) =>
        Some(s"Row $n says fork but $n.sql does not exist")
      case Row(n, Upstream(m)) if !files.contains(n) =>
        Some(s"Row $n says upstream:$m but $n.sql does not exist")
      case Row(n, Upstream(m)) if !files(n).contains(s"maproulette-backend $m.sql") =>
        Some(s"$n.sql must name its source, maproulette-backend $m.sql, in a comment")
      case _ => None
    }
    // Every upstream migration up to the last sync is either at its own number (<= base) or copied.
    val copied   = ledger.rows.collect { case Row(_, Upstream(m)) => m }
    val expected = (ledger.base + 1 to ledger.synced).toSet
    val upstreamChecks =
      (expected -- copied).toSeq.sorted.map { m =>
        s"Upstream $m.sql (synced up to ${ledger.synced}) has no upstream:$m row"
      } ++ copied.filterNot(expected).map { m =>
        s"upstream:$m is outside ${ledger.base + 1}..${ledger.synced}; check the base and synced lines"
      } ++ {
        val order = ledger.rows.sortBy(_.number).collect { case Row(_, Upstream(m)) => m }
        if (order == order.sorted) Nil else Seq("Upstream copies must keep upstream's order")
      }
    stray ++ gaps ++ dupes ++ belowBase ++ unlisted ++ rowChecks ++ upstreamChecks
  }
}

class EvolutionLedgerSpec extends PlaySpec {
  import EvolutionLedger._

  private def read(path: Path) = new String(Files.readAllBytes(path), "UTF-8")

  "The evolution ledger" should {
    "match conf/evolutions/default" in {
      val dir   = Paths.get("conf/evolutions/default")
      val names = Files.list(dir).iterator().asScala.map(_.getFileName.toString).toSeq
      val files = names.collect {
        case n if n.matches("[1-9][0-9]*\\.sql") =>
          n.stripSuffix(".sql").toInt -> read(dir.resolve(n))
      }.toMap
      val ledger = parse(read(Paths.get("conf/evolutions/ledger.txt"))).fold(e => fail(e), identity)
      problems(ledger, names, files) mustBe Nil
    }
  }

  "EvolutionLedger.problems" should {
    val ledger = Ledger(
      2,
      3,
      Seq(Row(3, Fork), Row(4, Upstream(3)), Row(5, Reserved))
    )
    val files = Map(
      1 -> "",
      2 -> "",
      3 -> "fork",
      4 -> "-- Upstream maproulette-backend 3.sql, renumbered"
    )
    "accept a consistent ledger" in {
      problems(ledger, Seq("1.sql", "2.sql", "3.sql", "4.sql"), files) mustBe Nil
    }
    "catch a gap, an unlisted file and a stray name" in {
      val more = files - 2 + (6 -> "")
      problems(ledger, Seq("6.sql", "0007.sql"), more) must contain allOf (
        "2.sql is missing; Play stops there",
        "6.sql has no ledger row. Claim the number in conf/evolutions/ledger.txt",
        "0007.sql is not named N.sql, so Play never reads it",
        "Reserved 5 is below the highest file 6.sql; it would leave a gap"
      )
    }
    "catch a file still marked reserved, and a fork row with no file" in {
      problems(
        ledger.copy(rows = Seq(Row(3, Fork), Row(4, Upstream(3)), Row(5, Reserved), Row(7, Fork))),
        Nil,
        files + (5 -> "")
      ) must contain allOf (
        "5.sql exists but its row says reserved. Change the row to fork",
        "Row 7 says fork but 7.sql does not exist"
      )
    }
    "catch an upstream migration that was synced but not copied" in {
      problems(ledger.copy(synced = 4), Nil, files) must contain(
        "Upstream 4.sql (synced up to 4) has no upstream:4 row"
      )
    }
    "catch an upstream copy that does not name its source" in {
      problems(ledger, Nil, files + (4 -> "")) must contain(
        "4.sql must name its source, maproulette-backend 3.sql, in a comment"
      )
    }
  }
}
