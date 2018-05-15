package streaming.dsl.mmlib.algs.feature

import _root_.streaming.dsl.mmlib.algs.{SQLDicOrTableToArray, SQLStringIndex, SQLTfIdf, SQLTokenAnalysis, SQLWord2Vec}
import org.apache.spark.sql.{functions => F}

import scala.collection.mutable.ArrayBuffer
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.ml.feature.NGram
import org.apache.spark.ml.help.HSQLStringIndex
import org.apache.spark.ml.linalg.{Vector, Vectors}
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.types.{StringType, StructField, StructType}
import org.apache.spark.sql.{Row, _}


/**
  * Created by allwefantasy on 14/5/2018.
  */
object StringFeature {

  private def replaceColumn(newDF: DataFrame, inputCol: String, udf: UserDefinedFunction) = {
    //newDF.withColumn(inputCol + "_tmp", udf(F.col(inputCol))).drop(inputCol).withColumnRenamed(inputCol + "_tmp", inputCol)
    newDF.withColumn(inputCol, udf(F.col(inputCol)))
  }

  private def loadStopwords(df: DataFrame, stopWordsPaths: String) = {
    val stopwords = if (stopWordsPaths == null || stopWordsPaths.isEmpty) {
      Set[String]()
    } else {
      val dtt = new SQLDicOrTableToArray()
      val stopwordsMapping = dtt.internal_train(df,
        Map(
          "dic.paths" -> stopWordsPaths,
          "dic.names" -> "stopwords"
        )).collect().map(f => (f.getString(0), f.getSeq(1))).toMap
      stopwordsMapping("stopwords").toSet[String]
    }
    stopwords
  }

  private def loadPriorityWords(df: DataFrame, priorityDicPaths: String,
                                priority: Double,
                                predictSingleWordFunc: String => Int) = {
    val priorityWords = (if (priorityDicPaths == null || priorityDicPaths.isEmpty) {
      Set[String]()

    } else {
      val dtt = new SQLDicOrTableToArray()
      val prioritywordsMapping = dtt.internal_train(df,
        Map(
          "dic.paths" -> priorityDicPaths,
          "dic.names" -> "prioritywords"
        )).collect().map(f => (f.getString(0), f.getSeq(1))).toMap
      prioritywordsMapping("prioritywords").toSet[String]
    }).map(f => predictSingleWordFunc(f)).filter(f => f != -1)

    val prioritywordsBr = df.sparkSession.sparkContext.broadcast(priorityWords)

    val priorityFunc = F.udf((vec: Vector) => {
      val indices = ArrayBuffer[Int]()
      val values = ArrayBuffer[Double]()
      vec.foreachActive { (index, value) =>
        val newValue = if (prioritywordsBr.value.contains(index)) {
          value * priority
        } else value
        indices += index
        values += newValue
      }
      Vectors.sparse(vec.size, indices.toArray, values.toArray)
    })

    (priorityWords, priorityFunc)
  }

  def analysisWords(df: DataFrame, mappingPath: String, dicPaths: String, inputCol: String,
                    stopwordsBr: Broadcast[Set[String]],
                    nGrams: Seq[Int],
                    outputWordAndIndex: Boolean
                   ) = {
    var newDF = new SQLTokenAnalysis().internal_train(df, Map("dic.paths" -> dicPaths, "inputCol" -> inputCol, "ignoreNature" -> "true"))
    val filterStopWordFunc = F.udf((a: Seq[String]) => {
      a.filterNot(stopwordsBr.value.contains(_))
    })
    newDF = replaceColumn(newDF, inputCol, filterStopWordFunc)
    //ngram support
    val ngramfields = nGrams.map { ngram =>
      val newField = inputCol + "_ngrams_" + ngram
      val ngramTF = new NGram().setN(ngram).setInputCol(inputCol).setOutputCol(newField)
      newDF = ngramTF.transform(newDF)
      newField
    }
    val mergeFunc = F.udf((a: Seq[String], b: Seq[String]) => {
      a ++ b
    })
    ngramfields.foreach { newField =>
      newDF = newDF.withColumn(inputCol, mergeFunc(F.col(inputCol), F.col(newField))).drop(newField)
    }

    val inputColIndex = newDF.schema.fieldIndex(inputCol)
    val newRdd = newDF.rdd.flatMap(f =>
      f.getSeq[String](inputColIndex)
    ).distinct().map(f =>
      Row.fromSeq(Seq(f))
    )

    //create uniq int for analysed token
    val tmpWords = df.sparkSession.createDataFrame(newRdd, StructType(Seq(StructField("words", StringType))))
    val wordCount = tmpWords.count()

    //represent content with sequence of number
    val wordIndexPath = mappingPath.stripSuffix("/") + s"/wordIndex/$inputCol"
    val si = new SQLStringIndex()
    si.train(tmpWords, wordIndexPath, Map("inputCol" -> "words"))


    val siModel = si.load(df.sparkSession, wordIndexPath, Map())

    if (outputWordAndIndex) {
      val wordToIndex = HSQLStringIndex.wordToIndex(df.sparkSession, siModel)
      val res = wordToIndex.toSeq.sortBy(f => f._2).map(f => s"${f._1}:${f._2}").mkString("\n")
      println(res)
    }


    val funcMap = si.internal_predict(df.sparkSession, siModel, "wow")
    val predictFunc = funcMap("wow_array").asInstanceOf[(Seq[String]) => Array[Int]]
    val udfPredictFunc = F.udf(predictFunc)
    newDF = replaceColumn(newDF, inputCol, udfPredictFunc)
    (newDF, funcMap, wordCount)
  }

  def tfidf(df: DataFrame,
            mappingPath: String,
            dicPaths: String,
            inputCol: String,
            stopWordsPaths: String,
            priorityDicPaths: String,
            priority: Double,
            nGrams: Seq[Int],
            outputWordAndIndex: Boolean = false
           ) = {

    //check stopwords dic is whether configured

    val stopwords = loadStopwords(df, stopWordsPaths)
    val stopwordsBr = df.sparkSession.sparkContext.broadcast(stopwords)

    //analysis
    var (newDF, funcMap, wordCount) = analysisWords(df, mappingPath, dicPaths, inputCol, stopwordsBr, nGrams, outputWordAndIndex)

    //tfidf feature
    val tfidfPath = mappingPath.stripSuffix("/") + s"/tfidf/$inputCol"
    val tfidf = new SQLTfIdf()
    tfidf.train(newDF, tfidfPath, Map("inputCol" -> inputCol, "numFeatures" -> wordCount.toString, "binary" -> "true"))
    val tfidfModel = tfidf.load(df.sparkSession, tfidfPath, Map())
    val tfidfFunc = tfidf.internal_predict(df.sparkSession, tfidfModel, "wow")("wow")
    val tfidfUDFFunc = F.udf(tfidfFunc)
    newDF = replaceColumn(newDF, inputCol, tfidfUDFFunc)

    //enhance
    val predictSingleWordFunc = funcMap("wow").asInstanceOf[(String) => Int]
    val (priorityWords, priorityFunc) = loadPriorityWords(newDF, priorityDicPaths, priority, predictSingleWordFunc)
    newDF = replaceColumn(newDF, inputCol, priorityFunc)

    newDF
  }

  def word2vec(df: DataFrame, mappingPath: String, dicPaths: String, inputCol: String, stopWordsPaths: String) = {

    val stopwords = loadStopwords(df, stopWordsPaths)
    val stopwordsBr = df.sparkSession.sparkContext.broadcast(stopwords)
    var (newDF, funcMap, wordCount) = analysisWords(df, mappingPath, dicPaths, inputCol, stopwordsBr, Seq(), false)

    // word2vec only accept String sequence, so we should convert int to str
    newDF = replaceColumn(newDF, inputCol, F.udf((a: Seq[Int]) => {
      a.map(f => f.toString)
    }))

    val word2vec = new SQLWord2Vec()
    val word2vecPath = mappingPath.stripSuffix("/") + s"/word2vec/$inputCol"
    word2vec.train(newDF, word2vecPath, Map("inputCol" -> inputCol, "minCount" -> "0"))
    val model = word2vec.load(df.sparkSession, word2vecPath, Map())
    val predictFunc = word2vec.internal_predict(df.sparkSession, model, "wow")("wow_array").asInstanceOf[(Seq[String]) => Seq[Seq[Double]]]
    val udfPredictFunc = F.udf(predictFunc)
    newDF = replaceColumn(newDF, inputCol, udfPredictFunc)
    newDF
  }

  def strToInt(df: DataFrame, mappingPath: String, inputCol: String, outputWordAndIndex: Boolean) = {
    val wordIndexPath = mappingPath.stripSuffix("/") + s"/wordIndex/$inputCol"
    val si = new SQLStringIndex()
    si.train(df, wordIndexPath, Map("inputCol" -> inputCol))
    val siModel = si.load(df.sparkSession, wordIndexPath, Map())

    if (outputWordAndIndex) {
      val wordToIndex = HSQLStringIndex.wordToIndex(df.sparkSession, siModel)
      val res = wordToIndex.toSeq.sortBy(f => f._2).map(f => s"${f._1}:${f._2}").mkString("\n")
      println(res)
    }
    val funcMap = si.internal_predict(df.sparkSession, siModel, "wow")
    val predictSingleWordFunc = funcMap("wow").asInstanceOf[(String) => Int]
    val newDF = replaceColumn(df, inputCol, F.udf(predictSingleWordFunc))
    (newDF, funcMap)
  }
}
