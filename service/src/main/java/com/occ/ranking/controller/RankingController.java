package com.occ.ranking.controller;

import java.sql.Timestamp;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.occ.ranking.model.TagNCount;
import com.occ.ranking.model.TrendInfo;
import com.occ.ranking.model.TweetInfo;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
public class RankingController {

    CqlSession session;

    @Autowired // Inject all 'Ranking' implementations
    RankingController() {
        log.info("Creating cassandra session");
        session = CqlSession.builder().build();
        log.info("Session created");
        Thread cleanup = new Thread(() -> {
                log.info("Closing cassandra session");
                session.close();
            }
        );
        Runtime.getRuntime().addShutdownHook(cleanup);
    }


    @PostMapping("/tweet")
    public Boolean gpost(@RequestParam(value = "tweet") String tweet) {
        List<TweetInfo> dataList = parseTweet(tweet);
        Properties properties = getPropery();
        KafkaProducer<String, String> producer =
                new KafkaProducer<String, String>(properties);
        for(TweetInfo data : dataList) {
            String concat = data.tweet + " |!| " + data.time;
            ProducerRecord<String, String> record =
                new ProducerRecord<String, String>("i1", data.hashtag, concat);
            producer.send(record);
        }
        producer.flush();
        producer.close();
        return false;
    }

    @GetMapping("/trending-hashtags")
    public TrendInfo getTrends(
        @RequestParam(value = "tstamp", defaultValue = "") String tstamp
    ) throws Exception {
        log.info("tstamp -> " + tstamp);
        if(tstamp.equals("")) {
            String latestTime = getLatestTime();
            return retieveCountFromDB(latestTime);
        }else {
            return retieveCountFromDB(tstamp);
        }
    }

    public TrendInfo retieveCountFromDB(String tstamp){
        ArrayList<TagNCount> lst = new ArrayList<TagNCount>();
        String query = "select hashtag, count from charter.trends_bycount where tstamp = " +
            String.format("'%s' limit 25", tstamp);
        log.info("Query = " + query);
        ResultSet result = session.execute(query);
        for(Row row: result){
            String hashtag = row.getString("hashtag");
            String count = row.getString("count");
            TagNCount info = new TagNCount();
            info.setTag(hashtag);
            info.setCount(count);
            lst.add(info);
        }
        TrendInfo trendInfo = new TrendInfo();
        trendInfo.setTime(tstamp);
        trendInfo.setCountList(lst);
        return trendInfo;
    }

    public String getLatestTime() throws Exception {
        String query = "select tstamp from charter.trends_tstamp where dummy = '-' limit 1";
        log.info("query -> " + query);
        ResultSet result = session.execute(query);
        Iterator<Row> iterator = result.iterator();
        if(iterator.hasNext()) {
            String tstamp = iterator.next().getString("tstamp");
            log.info("tstamp -> " + tstamp);
            return tstamp;
        }else {
            throw new Exception("No results available");
        }
    }

    public List<TweetInfo> parseTweet(String tweetWithHashTag) {
        String dtime = nearest5minutes(new Timestamp(System.currentTimeMillis()));
        List<TweetInfo> result = new ArrayList<TweetInfo>();
        String pattern = "(#[a-zA-Z0-9]+)";
        String tweet = tweetWithHashTag.replaceAll(pattern, "");
        log.info("tweetWithHashTag -> " + tweetWithHashTag + ", tweet -> " + tweet);
        Matcher m = Pattern.compile(pattern).matcher(tweetWithHashTag);
        log.info(String.valueOf(m.groupCount()));
        while(m.find()) {
            TweetInfo data = new TweetInfo();
            data.hashtag = m.group(1);
            data.tweet = tweet.trim();
            data.time = dtime;
            log.info("data -> " + data);
            result.add(data);
        }
        return result;
    }

    public String nearest5minutes(String str) throws ParseException {
        DateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        Date date = formatter.parse(str);
        Timestamp tstamp = new Timestamp(date.getTime());
        return nearest5minutes(tstamp);
    }

    public String nearest5minutes(Timestamp timestamp) {
        SimpleDateFormat hourFormat = new SimpleDateFormat("yyyy-MM-dd HH");
        SimpleDateFormat minuteFormat = new SimpleDateFormat("mm");
        Integer min = (Integer.parseInt(minuteFormat.format(timestamp)) / 5) * 5;
        String result = hourFormat.format(timestamp) + ":" + String.format("%02d", min);
        log.info("dtime -> " + result);
        return result;
    }

    public Properties getPropery() {
        String bootstrapServers = "127.0.0.1:9092";
        Properties properties = new Properties();
        properties.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class.getName());
        properties.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class.getName());
        return properties;
    }
}

