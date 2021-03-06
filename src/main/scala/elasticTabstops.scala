import scala.collection.SortedSet


package object elasticTabstops {

  // convenience functions to wrap Java's unintuitive split method
  def split(string: String, char: Char) = string.split(char.toString, -1)  // -1 so we get trailing empty strings
  def splitAndStrip(string: String, char: Char) = string.split(char)

  // Process runs of Some in list.
  // scala>    processAdjacent((l: List[Option[Int]]) => List.fill(l.length)(Some(l.flatten.max)),
  //                           List(Some(1), Some(2), None, Some(4), Some(5)))
  // res0: List[Option[Int]] = List(Some(2), Some(2), None, Some(5), Some(5))
  @annotation.tailrec
  def processAdjacent[A](process: List[Option[A]] => List[Option[A]],
                         unprocessed: List[Option[A]], processed: List[Option[A]] = Nil): List[Option[A]] =
    unprocessed.headOption match {
      case None => processed
      case Some(firstOfRun) => {
        val run = firstOfRun match {
          case None => unprocessed.takeWhile(_.isEmpty)
          case Some(_) => process(unprocessed.takeWhile(_.isDefined))
        }
        processAdjacent(process, unprocessed.drop(run.length), processed ::: run)
      }
    }

  // Replace each item in a run with its highest value.
  // scala>        maxAdjacent(List(Some(1), Some(2), None, Some(4), None, None, Some(7), Some(8), Some(9)))
  // res0: List[Option[Int]] = List(Some(2), Some(2), None, Some(4), None, None, Some(9), Some(9), Some(9))
  def maxAdjacent(list: List[Option[Int]]): List[Option[Int]] = {
    def fillMax = (l: List[Option[Int]]) => List.fill(l.length)(Some(l.flatten.max))
    processAdjacent(fillMax, list)
  }

  def calcMaxedWidthsPerLine(textWidthsPerLine: List[List[Int]]) : List[List[Int]] = {
    val maxNofCells = textWidthsPerLine.map(_.length).max

    val maxedWidthsPerColumn = (0 until maxNofCells).map(c =>
      maxAdjacent(textWidthsPerLine.map(_.dropRight(1).lift(c))))

    maxedWidthsPerColumn.toList.transpose.map(_.takeWhile(_.isDefined).flatten)
  }

  def measureWidthsPerLine(cellsPerLine: List[List[String]], measureText: String => Int): List[List[Int]] =
    cellsPerLine.map(_.map(measureText(_)))

  def calcTabstopPositions(cellsPerLine: List[List[String]], measureText: String => Int): List[List[Int]] = {
    val cellWidthsPerLine = measureWidthsPerLine(cellsPerLine, measureText)

    calcMaxedWidthsPerLine(cellWidthsPerLine).map(_.scanLeft(0)(_ + _).drop(1))
  }

  def tabsToSpaces(text: String, nofIndentSpaces: Int): String = {
    val cellPaddingWidthSpaces = 2  // must be at least 2 so we can convert back to tabs
    val cellMinimumWidthSpaces = nofIndentSpaces - cellPaddingWidthSpaces
    val cellsPerLine = split(text, '\n').map(splitAndStrip(_, '\t').toList).toList
    def calcCellWidth(text: String): Int = math.max(text.length, cellMinimumWidthSpaces) + cellPaddingWidthSpaces
    val maxedWidthsPerLine = calcMaxedWidthsPerLine(measureWidthsPerLine(cellsPerLine, calcCellWidth))

    maxedWidthsPerLine.zip(cellsPerLine).map { case (widthsThisLine, cellsThisLine) =>
      cellsThisLine.zip(widthsThisLine :+ 0).map { case (cellText, width) =>
        cellText + (" " * (width - cellText.length))
      }.mkString
    }.mkString("\n")
  }

  // Replace each item in a run with None if all of its contents are empty strings.
  // scala>      replaceEmptyRuns(List(Some(""), Some("a"), None, Some(""), Some("")))
  // res0: List[Option[String]] = List(Some(""), Some("a"), None, None,     None))
  def replaceEmptyRuns(list: List[Option[String]]): List[Option[String]] = {
    def nonesIfAllEmpty = (l: List[Option[String]]) =>
      if (l.forall(_.contains(""))) List.fill[Option[String]](l.length)(None) else l
    processAdjacent(nonesIfAllEmpty, list)
  }

  def spacesToTabs(text: String): String = {
    // a non-space followed by any number of chars that are either a non-space or a space followed by a non-space
    val cellTextRegEx = "[^ ](?:[^ ]| (?=[^ ]))*".r

    // prefix each line with a non-whitespace character (as we want to treat the start of each line as a column of text)
    val prefixedTextPerLine = split(text, '\n').map("|" + _)

    // get maps for each line containing the position of text and the text itself
    val matchesPerLine = prefixedTextPerLine.map(cellTextRegEx.findAllMatchIn(_).map(m => m.start -> m.matched).toMap)

    val allPositions = SortedSet(matchesPerLine.map(_.keys).toList.flatten: _*)

    // for each line, create matched or empty strings at every possible cell position
    val possCellsPerLine = matchesPerLine.map(matchesThisLine =>
      allPositions.toArray.map(position =>
        if (matchesThisLine.contains(position)) Some(matchesThisLine(position)) else Some("")))

    // we know that empty strings at the end of the line cannot be cells, so replace them with None
    val possCellsPerLine2 = possCellsPerLine.map { possCellsThisLine =>
      val nofTrailingEmpties = possCellsThisLine.reverse.takeWhile(_.contains("")).length
      possCellsThisLine.take(possCellsThisLine.length - nofTrailingEmpties) ++ List.fill(nofTrailingEmpties)(None)
    }

    // transpose, replace empty columns with Nones, transpose back, remove Nones, and join with tabs
    val textPerLine = possCellsPerLine2.toList.transpose.map(replaceEmptyRuns).transpose.map(_.flatten).map(_.mkString("\t"))

    // finally, drop previously inserted non-whitespace character from each line and join with newlines
    textPerLine.map(_.drop(1)).mkString("\n")
  }
}
