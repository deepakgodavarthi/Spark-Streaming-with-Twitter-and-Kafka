package streaming

import java.util.HashMap

import com.vader.sentiment.analyzer.SentimentAnalyzer
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.streaming.twitter.TwitterUtils
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.apache.spark.{SparkConf, SparkContext}
import twitter4j.Status

/**
  * A Kafka Producer that gets tweets on certain keywords
  * from twitter datasource and publishes to a kafka topic
  *
  * Arguments:  <KafkaTopic> <keyword_1> ... <keyword_n>
  * <KafkaTopic>		- The kafka topic to subscribe to
  * <keyword_1>		- The keyword to filter tweets
  * <keyword_n>		- Any number of keywords to filter tweets
  *
  */

object TwitterProducer {
  def main(args: Array[String]) {
    // You can find all functions used to process the stream in the
    // Utils.scala source file, whose contents we import here
    import Utils._

    /********
      * Let's check to make sure user has entered correct parameters
      *
      ********/
    if (args.length < 2) {
      System.out.println("Usage: TwitterProducer1 <KafkaTopic> <keyword1>")
      return
    }
    System.setProperty("twitter4j.oauth.consumerKey","k9C7m1OWQU32BjZL5aMvEn9GW")
    System.setProperty("twitter4j.oauth.consumerSecret", "WOn7MjDs9zejekww5GPBEwIou1T2ldkHtFEEMgbNSQoDEtTJmJ")
    System.setProperty("twitter4j.oauth.accessToken", "985652794312019968-7KHKzzcPuLDsTgAZ9VRVjsU9ZhSOUzr")
    System.setProperty("twitter4j.oauth.accessTokenSecret", "S35rxGjZWkphIMBDj6hz51UvEzGWRNjzC0Lh8YL9FNnSf")

    def sentiAna(tweet: TweetText): TweetText = {
      val sentimentAnalyzer: SentimentAnalyzer = new SentimentAnalyzer(tweet)
      sentimentAnalyzer.analyze()
      sentimentAnalyzer.getPolarity.toString
    }

    val topic = args(0).toString
    val filters = args.slice(1, args.length)
    val kafkaBrokers = "localhost:9092,localhost:9093"

    // First, let's configure Spark
    // We have to at least set an application name and master
    // If no master is given as part of the configuration we
    // will set it to be a local deployment running an
    // executor per thread
    val sparkConfiguration = new SparkConf().
      setAppName("spark-twitter-stream-example").
      setMaster(sys.env.get("spark.master").getOrElse("local[*]"))

    // Let's create the Spark Context using the configuration we just created
    val sparkContext = new SparkContext(sparkConfiguration)
    sparkContext.setLogLevel("ERROR")

    // Now let's wrap the context in a streaming one, passing along the window size
    val streamingContext = new StreamingContext(sparkContext, Seconds(5))


    val tweets: DStream[Status] =
      TwitterUtils.createStream(streamingContext, None, filters)

    // Let's extract the words of each tweet
    // We'll carry the tweet along in order to print it in the end
    val textAndSentences: DStream[(TweetText, Sentence)] =
    tweets.filter(x => x.getLang == "en").
      map(_.getText).
      map(tweetText => (sentiAna(tweetText), wordsOf(tweetText)))


    // write output to screen
    textAndSentences.print()

    // send data to Kafka broker

    textAndSentences.foreachRDD( rdd => {

      rdd.foreachPartition( partition => {
        // Print statements in this section are shown in the executor's stdout logs
        val props = new HashMap[String, Object]()
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBrokers)
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
          "org.apache.kafka.common.serialization.StringSerializer")
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
          "org.apache.kafka.common.serialization.StringSerializer")
        val producer = new KafkaProducer[String, String](props)
        partition.foreach( record => {
          val data = record.toString
          val splitData1 = data.split("compound=")
          val splitData2 = splitData1(1).split("}")(0)
          val message = new ProducerRecord[String, String](topic, null, splitData2)
          producer.send(message)
        } )
        producer.close()
      })

    })

    // Now that the streaming is defined, start it
    streamingContext.start()

    // Let's await the stream to end - forever
    streamingContext.awaitTermination()
  }

}
