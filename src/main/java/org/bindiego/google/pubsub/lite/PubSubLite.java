package org.bindiego.google.pubsub.lite;

import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bindiego.util.Config;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.ArrayList;
import java.util.List;

import io.grpc.Status.Code;
import io.grpc.StatusException;

import com.google.common.collect.Lists;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.api.gax.core.CredentialsProvider;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.rpc.ApiException;
import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutures;
import com.google.protobuf.ByteString;
import com.google.protobuf.util.Durations;

import com.google.cloud.pubsublite.AdminClient;
import com.google.cloud.pubsublite.AdminClientSettings;
import com.google.cloud.pubsublite.CloudRegion;
import com.google.cloud.pubsublite.CloudZone;
import com.google.cloud.pubsublite.ProjectNumber;
import com.google.cloud.pubsublite.TopicName;
import com.google.cloud.pubsublite.TopicPath;
import com.google.cloud.pubsublite.proto.Topic;
import com.google.cloud.pubsublite.proto.Topic.PartitionConfig;
import com.google.cloud.pubsublite.proto.Topic.RetentionConfig;
import com.google.cloud.pubsublite.SubscriptionName;
import com.google.cloud.pubsublite.SubscriptionPath;
import com.google.cloud.pubsublite.proto.Subscription;
import com.google.cloud.pubsublite.proto.Subscription.DeliveryConfig;
import com.google.cloud.pubsublite.proto.Subscription.DeliveryConfig.DeliveryRequirement;

public class PubSubLite extends Thread {
    public PubSubLite() {
        try {
            // Instantiate or get the current Global config
            this.config = Config.getConfig();

            long projectNum = Long.parseLong(
                config.getProperty("google.projectnum").toString());
            String region = config.getProperty("google.pubsublite.region").toString();
            char zoneId = config.getProperty("google.pubsublite.zone").toString().charAt(0);

            String topicId = config.getProperty("google.pubsublite.topic").toString();
            this.numPartitions = Integer.valueOf(
                config.getProperty("google.pubsublite.partitions").toString());

            String subId = config.getProperty("google.pubsublite.subscription").toString();

            this.topicPath =
                TopicPath.newBuilder()
                    .setProject(ProjectNumber.of(projectNum))
                    .setLocation(CloudZone.of(CloudRegion.of(region), zoneId))
                    .setName(TopicName.of(topicId))
                    .build();

            this.subscriptionPath =
                SubscriptionPath.newBuilder()
                    .setProject(ProjectNumber.of(projectNum))
                    .setLocation(CloudZone.of(CloudRegion.of(region), zoneId))
                    .setName(SubscriptionName.of(subId))
                    .build();

            this.topic =
                Topic.newBuilder()
                    .setPartitionConfig(
                        PartitionConfig.newBuilder()
                        // Set publishing throughput to 1 times the standard partition
                        // throughput of 4 MiB per sec. This must be in the range [1,4]. A
                        // topic with `scale` of 2 and count of 10 is charged for 20 partitions.
                        .setScale(Integer.parseInt(
                            config.getProperty("google.pubsublite.partitions.scale").toString()))
                        .setCount(this.numPartitions))
                    .setRetentionConfig(
                        RetentionConfig.newBuilder()
                            // How long messages are retained.
                            .setPeriod(Durations.fromDays(
                                Integer.parseInt(
                                    config.getProperty("google.pubsublite.retention.days").toString())))
                            // Set storage per partition to 100 GiB. This must be 30 GiB-10 TiB.
                            // If the number of bytes stored in any of the topic's partitions grows
                            // beyond this value, older messages will be dropped to make room for
                            // newer ones, regardless of the value of `period`.
                            .setPerPartitionBytes(
                                Integer.parseInt(
                                    config.getProperty("google.pubsublite.partitions.storage.gb").toString())
                                * 1024 * 1024 * 1024L))
                    .setName(topicPath.toString())
                    .build();

            this.subscription =
                Subscription.newBuilder()
                    .setDeliveryConfig(
                        // The server does not wait for a published message to be successfully
                        // written to storage before delivering it to subscribers. As such, a
                        // subscriber may receive a message for which the write to storage failed.
                        // If the subscriber re-reads the offset of that message later on, there
                        // may be a gap at that offset.
                        DeliveryConfig.newBuilder()
                            .setDeliveryRequirement(DeliveryRequirement.DELIVER_IMMEDIATELY))
                    .setName(subscriptionPath.toString())
                    .setTopic(topicPath.toString())
                    .build();

            this.adminClientSettings =
                AdminClientSettings.newBuilder().setRegion(CloudRegion.of(region)).build();

            this.adminClient = AdminClient.create(this.adminClientSettings);
        } catch (Exception ex) {
            logger.error("Error", ex);
        }
    }

    /**
     * Pre-setup using gcloud command
     */
    private void init(CredentialsProvider credentialsProvider) {
        logger.info("Setup the pubsub topic and subscription");

        logger.info("creating topic");
        try {
            Topic response = adminClient.createTopic(this.topic).get();

            logger.info("%s created.\n", response.getAllFields());
        } catch (InterruptedException ex) {
            logger.error("Interrupted Error", ex);
        } catch (ExecutionException ex) {
            logger.error("Excecution Error", ex);
        } catch (ApiException ex) {
            if (ex.isRetryable())
                logger.debug("TODO: operation retryable");
            logger.error("Status code: " + ex.getStatusCode().getCode());
            logger.error("API Exception", ex);
        }

        logger.info("creating the subscription");
        try {
            Subscription response = adminClient.createSubscription(this.subscription).get();

            logger.info("%s created.\n", response.getAllFields());
        } catch (InterruptedException ex) {
            logger.error("Interrupted Error", ex);
        } catch (ExecutionException ex) {
            logger.error("Excecution Error", ex);
        } catch (ApiException ex) {
            if (ex.isRetryable())
                logger.debug("TODO: operation retryable");
            logger.error("Status code: " + ex.getStatusCode().getCode());
            logger.error("API Exception", ex);
        }
    }

    @Override
    public void run() {
        // Explicitly load google service account credentials
        String gCredentials = config.getProperty("google.credentials").toString();
        CredentialsProvider credentialsProvider = null;
        try {
            credentialsProvider = FixedCredentialsProvider.create(
                ServiceAccountCredentials.fromStream(
                    new FileInputStream(gCredentials)));
        } catch (Exception ex) {
            logger.fatal("Credential loading failed", ex);
        }

        if (!Boolean.valueOf(config.getProperty("google.pubsublite.skip.init").toString())) {
            init(credentialsProvider);
        } else {
            logger.info("Skip creation of lite topic and subscriptions");
        }

        // Run publisher threads
        int numPubThreads = Integer.parseInt(
            config.getProperty("google.pubsublite.pub.threads").toString());
        execPub = Executors.newFixedThreadPool(numPubThreads);

        for (int i = 0; i < numPubThreads; ++i) {
            execPub.execute(
                new DoPubLite(
                    this.topicPath,
                    credentialsProvider));
        }

        // Run subscriber threads
        if (config.getProperty("google.pubsublite.sub.threads.dedicated.partition").toString().equalsIgnoreCase("off")) {
            // every thread will consume from all partitions
            int numSubThreads = Integer.parseInt(
                config.getProperty("google.pubsublite.sub.threads").toString());
            execSub = Executors.newFixedThreadPool(numSubThreads);

            List<Integer> partitionNumbers = new ArrayList<>();
            for (int i = 0; i < this.numPartitions.intValue(); ++i) {
                partitionNumbers.add(Integer.valueOf(i));
            }

            for (int i = 0; i < numSubThreads; ++i) {
                execSub.execute(
                    new DoSubLite(
                        this.subscriptionPath,
                        credentialsProvider,
                        partitionNumbers));
            }
        } else if (config.getProperty("google.pubsublite.sub.threads.dedicated.partition").toString().equalsIgnoreCase("on")) {
            // FIXME: each thread will only consume one partition, so numThreads = numPartitions
            int numSubThreads = this.numPartitions.intValue();
            execSub = Executors.newFixedThreadPool(numSubThreads);

            for (int i = 0; i < numSubThreads; ++i) {
                List<Integer> partitionNumbers = new ArrayList<>();
                partitionNumbers.add(Integer.valueOf(i));

                execSub.execute(
                    new DoSubLite(
                        this.subscriptionPath,
                        credentialsProvider,
                        partitionNumbers));
            }
        }

        execPub.shutdown();
        execSub.shutdown();
    }

    private static final Logger logger =
        LogManager.getFormatterLogger(PubSubLite.class.getName());

    private PropertiesConfiguration config;

    private ExecutorService execPub;
    private BlockingQueue<Runnable> pubbq;

    private ExecutorService execSub;
    private BlockingQueue<Runnable> subbq;

    private TopicPath topicPath;
    private Topic topic;
    private SubscriptionPath subscriptionPath;
    private Subscription subscription;

    private AdminClientSettings adminClientSettings;
    private AdminClient adminClient;

    private Integer numPartitions;
}
