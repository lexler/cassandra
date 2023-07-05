/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.tcm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.config.CassandraRelevantProperties;
import org.apache.cassandra.exceptions.ExceptionCode;
import org.apache.cassandra.io.util.FileInputStreamPlus;
import org.apache.cassandra.io.util.FileOutputStreamPlus;
import org.apache.cassandra.locator.InetAddressAndPort;
import org.apache.cassandra.metrics.TCMMetrics;
import org.apache.cassandra.net.IVerbHandler;
import org.apache.cassandra.net.NoPayload;
import org.apache.cassandra.schema.DistributedSchema;
import org.apache.cassandra.tcm.log.Entry;
import org.apache.cassandra.tcm.log.LocalLog;
import org.apache.cassandra.tcm.log.LogState;
import org.apache.cassandra.tcm.log.LogStorage;
import org.apache.cassandra.tcm.log.Replication;
import org.apache.cassandra.tcm.membership.NodeId;
import org.apache.cassandra.tcm.membership.NodeVersion;
import org.apache.cassandra.tcm.migration.Election;
import org.apache.cassandra.tcm.migration.GossipProcessor;
import org.apache.cassandra.tcm.ownership.PlacementProvider;
import org.apache.cassandra.tcm.ownership.UniformRangePlacement;
import org.apache.cassandra.tcm.sequences.AddToCMS;
import org.apache.cassandra.tcm.sequences.ProgressBarrier;
import org.apache.cassandra.tcm.serialization.VerboseMetadataSerializer;
import org.apache.cassandra.tcm.serialization.Version;
import org.apache.cassandra.tcm.transformations.ForceSnapshot;
import org.apache.cassandra.tcm.transformations.SealPeriod;
import org.apache.cassandra.tcm.transformations.cms.EntireRange;
import org.apache.cassandra.tcm.transformations.cms.RemoveFromCMS;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.Pair;
import org.apache.cassandra.utils.concurrent.Future;
import org.apache.cassandra.utils.concurrent.ImmediateFuture;

import static java.util.stream.Collectors.toSet;
import static org.apache.cassandra.tcm.ClusterMetadataService.State.*;
import static org.apache.cassandra.tcm.compatibility.GossipHelper.emptyWithSchemaFromSystemTables;
import static org.apache.cassandra.utils.Collectors3.toImmutableSet;


public class ClusterMetadataService
{
    private static final Logger logger = LoggerFactory.getLogger(ClusterMetadataService.class);

    private static ClusterMetadataService instance;
    private static Throwable trace;
    public static final TCMMetrics metrics = new TCMMetrics();

    public static void setInstance(ClusterMetadataService newInstance)
    {
        if (instance != null)
            throw new IllegalStateException(String.format("Cluster metadata is already initialized to %s.", instance),
                                            trace);
        instance = newInstance;
        trace = new RuntimeException("Previously initialized trace");
    }

    @VisibleForTesting
    public static ClusterMetadataService unsetInstance()
    {
        ClusterMetadataService tmp = instance();
        instance = null;
        return tmp;
    }

    public static ClusterMetadataService instance()
    {
        return instance;
    }

    private final PlacementProvider placementProvider;
    private final Processor processor;
    private final LocalLog log;
    private final MetadataSnapshots snapshots;

    private final Replication.ReplicationHandler replicationHandler;
    private final Replication.LogNotifyHandler logNotifyHandler;
    private final IVerbHandler<FetchCMSLog> fetchLogHandler;
    private final IVerbHandler<Commit> commitRequestHandler;
    private final IVerbHandler<NoPayload> currentEpochHandler;

    private final PeerLogFetcher peerLogFetcher;

    private final AtomicBoolean commitsPaused = new AtomicBoolean();

    public static State state()
    {
        return state(ClusterMetadata.current());
    }

    public static State state(ClusterMetadata metadata)
    {
        if (CassandraRelevantProperties.TCM_UNSAFE_BOOT_WITH_CLUSTERMETADATA.isPresent())
            return RESET;

        if (metadata.epoch.isBefore(Epoch.EMPTY))
            return GOSSIP;

        // The node is a full member of the CMS if it has started participating in reads for distributed metadata table (which
        // implies it is a write replica as well). In other words, it's a fully joined member of the replica set responsible for
        // the distributed metadata table.
        if (ClusterMetadata.current().isCMSMember(FBUtilities.getBroadcastAddressAndPort()))
            return LOCAL;
        return REMOTE;
    }

    ClusterMetadataService(PlacementProvider placementProvider,
                           ClusterMetadata initial,
                           Function<Processor, Processor> wrapProcessor,
                           Supplier<State> cmsStateSupplier,
                           boolean isReset)
    {
        this.placementProvider = placementProvider;
        this.snapshots = new MetadataSnapshots.SystemKeyspaceMetadataSnapshots();

        Processor localProcessor;
        if (CassandraRelevantProperties.TCM_USE_ATOMIC_LONG_PROCESSOR.getBoolean())
        {
            LogStorage logStorage = LogStorage.SystemKeyspace;
            log = LocalLog.sync(initial, logStorage, true, isReset);
            localProcessor = wrapProcessor.apply(new AtomicLongBackedProcessor(log));
            fetchLogHandler = new FetchCMSLog.Handler(logStorage::getLogState);
        }
        else
        {

            log = LocalLog.async(initial, isReset);
            localProcessor = wrapProcessor.apply(new PaxosBackedProcessor(log));
            fetchLogHandler = new FetchCMSLog.Handler();
        }

        Commit.Replicator replicator = CassandraRelevantProperties.TCM_USE_NO_OP_REPLICATOR.getBoolean()
                                       ? Commit.Replicator.NO_OP
                                       : new Commit.DefaultReplicator(() -> log.metadata().directory);

        RemoteProcessor remoteProcessor = new RemoteProcessor(log, Discovery.instance::discoveredNodes);
        GossipProcessor gossipProcessor = new GossipProcessor();
        currentEpochHandler = new CurrentEpochRequestHandler();

        commitRequestHandler = new Commit.Handler(localProcessor, replicator, cmsStateSupplier);
        processor = new SwitchableProcessor(localProcessor,
                                            remoteProcessor,
                                            gossipProcessor,
                                            replicator,
                                            cmsStateSupplier);

        replicationHandler = new Replication.ReplicationHandler(log);
        logNotifyHandler = new Replication.LogNotifyHandler(log);
        peerLogFetcher = new PeerLogFetcher(log);
    }

    @VisibleForTesting
    // todo: convert this to a factory method with an obvious name that this is just for testing
    public ClusterMetadataService(PlacementProvider placementProvider,
                                  MetadataSnapshots snapshots,
                                  LocalLog log,
                                  Processor processor,
                                  Commit.Replicator replicator,
                                  boolean isMemberOfOwnershipGroup)
    {
        this.placementProvider = placementProvider;
        this.log = log;
        this.processor = new SwitchableProcessor(processor, null, null, replicator, () -> State.LOCAL);
        this.snapshots = snapshots;

        replicationHandler = new Replication.ReplicationHandler(log);
        logNotifyHandler = new Replication.LogNotifyHandler(log);
        currentEpochHandler = new CurrentEpochRequestHandler();

        fetchLogHandler = isMemberOfOwnershipGroup ? new FetchCMSLog.Handler() : null;
        commitRequestHandler = isMemberOfOwnershipGroup ? new Commit.Handler(processor, replicator, () -> LOCAL) : null;

        peerLogFetcher = new PeerLogFetcher(log);
    }

    private ClusterMetadataService(PlacementProvider placementProvider,
                                   MetadataSnapshots snapshots,
                                   LocalLog log,
                                   Processor processor,
                                   Replication.ReplicationHandler replicationHandler,
                                   Replication.LogNotifyHandler logNotifyHandler,
                                   CurrentEpochRequestHandler currentEpochHandler,
                                   FetchCMSLog.Handler fetchLogHandler,
                                   Commit.Handler commitRequestHandler,
                                   PeerLogFetcher peerLogFetcher)
    {
        this.placementProvider = placementProvider;
        this.snapshots = snapshots;
        this.log = log;
        this.processor = processor;
        this.replicationHandler = replicationHandler;
        this.logNotifyHandler = logNotifyHandler;
        this.currentEpochHandler = currentEpochHandler;
        this.fetchLogHandler = fetchLogHandler;
        this.commitRequestHandler = commitRequestHandler;
        this.peerLogFetcher = peerLogFetcher;
    }

    @SuppressWarnings("resource")
    public static void initializeForTools(boolean loadSSTables)
    {
        if (instance != null)
            return;
        ClusterMetadata emptyFromSystemTables = emptyWithSchemaFromSystemTables();
        emptyFromSystemTables.schema.initializeKeyspaceInstances(DistributedSchema.empty(), loadSSTables);
        emptyFromSystemTables = emptyFromSystemTables.forceEpoch(Epoch.EMPTY);
        LocalLog log = LocalLog.sync(emptyFromSystemTables, new AtomicLongBackedProcessor.InMemoryStorage(), false, false);
        ClusterMetadataService cms = new ClusterMetadataService(new UniformRangePlacement(),
                                                                MetadataSnapshots.NO_OP,
                                                                log,
                                                                new AtomicLongBackedProcessor(log),
                                                                new Replication.ReplicationHandler(log),
                                                                new Replication.LogNotifyHandler(log),
                                                                new CurrentEpochRequestHandler(),
                                                                null,
                                                                null,
                                                                new PeerLogFetcher(log));
        log.bootstrap(FBUtilities.getBroadcastAddressAndPort());
        ClusterMetadataService.setInstance(cms);
    }

    @SuppressWarnings("resource")
    public static void initializeForClients()
    {
        if (instance != null)
            return;

        ClusterMetadataService.setInstance(StubClusterMetadataService.forClientTools());
    }

    public boolean isCurrentMember(InetAddressAndPort peer)
    {
        return ClusterMetadata.current().isCMSMember(peer);
    }

    public void removeFromCms(boolean force)
    {
        ClusterMetadata metadata = metadata();
        Set<InetAddressAndPort> existingMembers = metadata.fullCMSMembers();
        InetAddressAndPort local = FBUtilities.getBroadcastAddressAndPort();
        if (!existingMembers.contains(FBUtilities.getBroadcastAddressAndPort()))
        {
            logger.info("Not a CMS member");
            throw new IllegalStateException("Not a CMS member");
        }

        int minSafeSize = RemoveFromCMS.MIN_SAFE_CMS_SIZE;
        if ((existingMembers.size() <= minSafeSize) && !force)
        {
            String msg = String.format("Shrinking CMS size below %d requires the force option", minSafeSize);
            logger.info(msg);
            throw new IllegalStateException(msg);
        }

        Epoch epoch = ClusterMetadataService.instance().commit(new RemoveFromCMS(local, force)).epoch;
        // Awaiting on the progress barrier will leave a log message in case it could not collect a majority. But we do not
        // want to block the operation at that point, since for the purpose of executing CMS operations, we have already
        // stopped being a CMS node, and for the purpose of either continuing or starting a leave sequence, we will not
        // be able to collect a majority of CMS nodes during commit.
        new ProgressBarrier(epoch, EntireRange.affectedRanges).await();
    }

    public void addToCms(List<String> ignoredEndpoints)
    {
        Set<InetAddressAndPort> ignored = ignoredEndpoints.stream().map(InetAddressAndPort::getByNameUnchecked).collect(toSet());
        if (ignored.contains(FBUtilities.getBroadcastAddressAndPort()))
        {
            String msg = "Can't ignore local host " + FBUtilities.getBroadcastAddressAndPort() + " when doing CMS migration";
            logger.error(msg);
            throw new IllegalStateException(msg);
        }

        ClusterMetadata metadata = metadata();
        Set<InetAddressAndPort> existingMembers = metadata.fullCMSMembers();
        if (existingMembers.contains(FBUtilities.getBroadcastAddressAndPort()))
        {
            logger.info("Already in the CMS");
            throw new IllegalStateException("Already in the CMS");
        }

        if (!metadata.directory.allAddresses().containsAll(ignored))
        {
            Set<InetAddressAndPort> allAddresses = Sets.newHashSet(metadata.directory.allAddresses());
            String msg = String.format("Ignored host(s) %s don't exist in the cluster", Sets.difference(ignored, allAddresses));
            logger.error(msg);
            throw new IllegalStateException(msg);
        }

        for (Map.Entry<NodeId, NodeVersion> entry : metadata.directory.versions.entrySet())
        {
            NodeVersion version = entry.getValue();
            InetAddressAndPort ep = metadata.directory.getNodeAddresses(entry.getKey()).broadcastAddress;
            if (ignored.contains(ep))
            {
                // todo; what do we do if an endpoint has a mismatching gossip-clustermetadata?
                //       - we could add the node to --ignore and force this CM to it?
                //       - require operator to bounce/manually fix the CM on that node
                //       for now just requiring that any ignored host is also down
//                if (FailureDetector.instance.isAlive(ep))
//                    throw new IllegalStateException("Can't ignore " + ep + " during CMS migration - it is not down");
                logger.info("Endpoint {} running {} is ignored", ep, version);
                continue;
            }

            if (!version.isUpgraded())
            {
                String msg = String.format("All nodes are not yet upgraded - %s is running %s", metadata.directory.endpoint(entry.getKey()), version);
                logger.error(msg);
                throw new IllegalStateException(msg);
            }
        }

        if (existingMembers.isEmpty())
        {
            logger.info("First CMS node");
            Set<InetAddressAndPort> candidates = metadata
                                                 .directory
                                                 .allAddresses()
                                                 .stream()
                                                 .filter(ep -> !FBUtilities.getBroadcastAddressAndPort().equals(ep) &&
                                                               !ignored.contains(ep))
                                                 .collect(toImmutableSet());

            Election.instance.nominateSelf(candidates, ignored, metadata::equals);
            ClusterMetadataService.instance().sealPeriod();
        }
        else
        {
            logger.info("Adding local node to existing CMS nodes; {}", existingMembers);
            AddToCMS.initiate();
        }
    }

    public boolean applyFromGossip(ClusterMetadata expected, ClusterMetadata updated)
    {
        logger.debug("Applying from gossip, current={} new={}", expected, updated);
        if (!expected.epoch.isBefore(Epoch.EMPTY))
            throw new IllegalStateException("Can't apply a ClusterMetadata from gossip with epoch " + expected.epoch);
        if (state() != GOSSIP)
            throw new IllegalStateException("Can't apply a ClusterMetadata from gossip when CMSState is not GOSSIP: " + state());

        return log.unsafeSetCommittedFromGossip(expected, updated);
    }

    public void setFromGossip(ClusterMetadata fromGossip)
    {
        logger.debug("Setting from gossip, new={}", fromGossip);
        if (state() != GOSSIP)
            throw new IllegalStateException("Can't apply a ClusterMetadata from gossip when CMSState is not GOSSIP: " + state());
        log.unsafeSetCommittedFromGossip(fromGossip);
    }

    public void forceSnapshot(ClusterMetadata snapshot)
    {
        commit(new ForceSnapshot(snapshot));
    }

    public void revertToEpoch(Epoch epoch)
    {
        logger.warn("Reverting to epoch {}", epoch);
        ClusterMetadata metadata = ClusterMetadata.current();
        ClusterMetadata toApply = transformSnapshot(LogState.getForRecovery(epoch))
                                  .forceEpoch(metadata.epoch.nextEpoch())
                                  .forcePeriod(metadata.nextPeriod());
        forceSnapshot(toApply);
    }

    /**
     * dumps the cluster metadata at the given epoch, returns path to the generated file
     *
     * if the given Epoch is EMPTY, we dump the current metadata
     *
     * @param epoch dump clustermetadata at this epoch
     * @param transformToEpoch transform the dumped metadata to this epoch
     * @param version serialisation version
     */
    public String dumpClusterMetadata(Epoch epoch, Epoch transformToEpoch, Version version) throws IOException
    {
        ClusterMetadata toDump = epoch.isAfter(Epoch.EMPTY)
                                 ? transformSnapshot(LogState.getForRecovery(epoch))
                                 : ClusterMetadata.current();
        toDump = toDump.forceEpoch(transformToEpoch);
        Path p = Files.createTempFile("clustermetadata", "dump");
        try (FileOutputStreamPlus out = new FileOutputStreamPlus(p))
        {
            VerboseMetadataSerializer.serialize(ClusterMetadata.serializer, toDump, out, version);
        }
        logger.info("Dumped cluster metadata to {}", p.toString());
        return p.toString();
    }

    public void loadClusterMetadata(String file) throws IOException
    {
        logger.warn("Loading cluster metadata from {}", file);
        ClusterMetadata metadata = ClusterMetadata.current();
        ClusterMetadata toApply = deserializeClusterMetadata(file)
                                  .forceEpoch(metadata.epoch.nextEpoch())
                                  .forcePeriod(metadata.nextPeriod());
        forceSnapshot(toApply);
    }

    public static ClusterMetadata deserializeClusterMetadata(String file) throws IOException
    {
        try (FileInputStreamPlus fisp = new FileInputStreamPlus(file))
        {
            return VerboseMetadataSerializer.deserialize(ClusterMetadata.serializer, fisp);
        }
    }

    private ClusterMetadata transformSnapshot(LogState state)
    {
        ClusterMetadata toApply = state.baseState;
        for (Entry entry : state.transformations.entries())
        {
            Transformation.Result res = entry.transform.execute(toApply);
            assert res.isSuccess();
            toApply = res.success().metadata;
        }
        return toApply;
    }

    public final Supplier<Entry.Id> entryIdGen = new Entry.DefaultEntryIdGen();

    public ClusterMetadata commit(Transformation transform)
    {
        return commit(transform,
                      (metadata) -> false,
                      (metadata) -> metadata,
                      (metadata, code, reason) -> {
                          throw new IllegalStateException(reason);
                      });
    }

    public interface CommitSuccessHandler<T>
    {
        T accept(ClusterMetadata latest);
    }

    public interface CommitRejectionHandler<T>
    {
        T accept(ClusterMetadata latest, ExceptionCode code, String message);
    }

    public <T1> T1 commit(Transformation transform, Predicate<ClusterMetadata> retry, CommitSuccessHandler<T1> onSuccess, CommitRejectionHandler<T1> onReject)
    {
        if (commitsPaused.get())
            throw new IllegalStateException("Commits are paused, not trying to commit " + transform);
        Retry.Backoff backoff = new Retry.Backoff();
        while (!backoff.reachedMax())
        {
            Commit.Result result = processor.commit(entryIdGen.get(), transform, null);

            if (result.isSuccess())
            {
                while (!backoff.reachedMax())
                {
                    try
                    {
                        return onSuccess.accept(awaitAtLeast(result.success().epoch));
                    }
                    catch (TimeoutException t)
                    {
                        logger.error("Timed out while waiting for the follower to enact the epoch {}", result.success().epoch, t);
                        backoff.maybeSleep();
                    }
                    catch (InterruptedException e)
                    {
                        throw new IllegalStateException("Couldn't commit the transformation. Is the node shutting down?", e);
                    }
                }
            }
            else
            {
                ClusterMetadata metadata = fetchLogFromCMS();

                if (result.failure().rejected)
                    return onReject.accept(metadata, result.failure().code, result.failure().message);

                if (!retry.test(metadata))
                    throw new IllegalStateException(String.format("Committing transformation %s failed and retry criteria was not satisfied. Current tries: %s", transform, backoff.tries + 1));

                logger.info("Couldn't commit the transformation due to \"{}\". Retrying again in {}ms.", result.failure().message, backoff.backoffMs);
                // Back-off and retry
                backoff.maybeSleep();
            }
        }

        if (backoff.reachedMax())
            throw new IllegalStateException(String.format("Couldn't commit the transformation %s after %d tries", transform, backoff.maxTries()));

        throw new IllegalStateException(String.format("Could not succeed committing %s after %d tries", transform, backoff.maxTries));
    }

    /**
     * Accessors
     */

    public static Replication.ReplicationHandler replicationHandler()
    {
        // Make it possible to get Verb without throwing NPE during simulation
        ClusterMetadataService instance = ClusterMetadataService.instance();
        if (instance == null)
            return null;
        return instance.replicationHandler;
    }

    public static Replication.LogNotifyHandler logNotifyHandler()
    {
        // Make it possible to get Verb without throwing NPE during simulation
        ClusterMetadataService instance = ClusterMetadataService.instance();
        if (instance == null)
            return null;
        return instance.logNotifyHandler;
    }

    public static IVerbHandler<FetchCMSLog> fetchLogRequestHandler()
    {
        // Make it possible to get Verb without throwing NPE during simulation
        ClusterMetadataService instance = ClusterMetadataService.instance();
        if (instance == null)
            return null;
        return instance.fetchLogHandler;
    }

    public static IVerbHandler<Commit> commitRequestHandler()
    {
        // Make it possible to get Verb without throwing NPE during simulation
        ClusterMetadataService instance = ClusterMetadataService.instance();
        if (instance == null)
            return null;
        return instance.commitRequestHandler;
    }

    public static IVerbHandler<NoPayload> currentEpochRequestHandler()
    {
        // Make it possible to get Verb without throwing NPE during simulation
        ClusterMetadataService instance = ClusterMetadataService.instance();
        if (instance == null)
            return null;
        return instance.currentEpochHandler;
    }

    public PlacementProvider placementProvider()
    {
        return this.placementProvider;
    }

    @VisibleForTesting
    public Processor processor()
    {
        return processor;
    }

    @VisibleForTesting
    public LocalLog log()
    {
        return log;
    }

    public ClusterMetadata metadata()
    {
        return log.metadata();
    }

    public boolean maybeFetchLog(Epoch theirEpoch)
    {
        if (theirEpoch.isBefore(Epoch.FIRST))
            return false;

        Epoch ourEpoch = ClusterMetadata.current().epoch;

        if (ourEpoch.isEqualOrAfter(theirEpoch))
            return true;

        for (int i = 0; i < 2; i++)
        {
            State state = state();
            if (EnumSet.of(GOSSIP, RESET).contains(state))
            {
                //TODO we have seen a message with epoch > EMPTY, we are probably racing with migration,
                //     or we missed the finish migration message, handle!
                logger.warn("Cannot fetch log while in {} mode (target epoch = {})", state, theirEpoch);
                return false;
            }

            fetchLogFromCMS();
            ourEpoch = ClusterMetadata.current().epoch;
            if (ourEpoch.isEqualOrAfter(theirEpoch))
                return true;
        }

        throw new IllegalStateException(String.format("Could not catch up to epoch %s even after fetching log from CMS. Highest seen after fetching is %s.",
                                                      theirEpoch, ourEpoch));
    }

    /**
     * Fetches log entries from directly from CMS, up to the highest currently known epoch.
     * This operation is blocking and also waits for all retrieved log entries to be
     * enacted, so on return all transformations to ClusterMetadata will be visible.
     * @return metadata with all currently committed entries enacted.
     */
    public ClusterMetadata fetchLogFromCMS()
    {
        return processor.fetchLogAndWait();
    }

    /**
     * Attempts to asynchronously retrieve log entries from a non-CMS peer.
     * Fetches and applies the log state representing the delta between the current local epoch and the one requested.
     * This is used when a message from a peer contains an epoch higher than the current local epoch. As the sender of
     * the message must have seen and enacted the given epoch, they must (under normal circumstances) be able to supply
     * any entries needed to catch up this node.
     * When the returned future completes, the metadata it provides is the current published metadata at the
     * moment of completion. In the expected case, this will have had any fetched transformations up to the requested
     * epoch applied. If the fetch was unsuccessful (e.g. because the peer was unavailable) it will still be whatever
     * the currently published metadata, but which entries have been enacted cannot be guaranteed.
     * @param from peer to request log entries from
     * @param awaitAtLeast the upper epoch required. It's expected that the peer is able to supply log entries up to at
     *                     least this epoch.
     * @return A future which will supply the current ClusterMetadata at the time of completion
     */
    public Future<ClusterMetadata> fetchLogFromPeerAsync(InetAddressAndPort from, Epoch awaitAtLeast)
    {
        ClusterMetadata current = ClusterMetadata.current();
        if (awaitAtLeast.isBefore(Epoch.FIRST) || current.epoch.isEqualOrAfter(awaitAtLeast))
            return ImmediateFuture.success(current);

        logger.info("Fetching log async from {}, at least = {}", from, awaitAtLeast);
        return peerLogFetcher.asyncFetchLog(from, awaitAtLeast);
    }

    /**
     * Attempts to synchronously retrieve log entries from a non-CMS peer.
     * Fetches the log state representing the delta between the current local epoch and the one supplied.
     * This is to be used when a message from a peer contains an epoch higher than the current local epoch. As
     * sender of the message must have seen and enacted the given epoch, they must (under normal circumstances)
     * be able to supply any entries needed to catch up this node.
     * The metadata returned is the current published metadata at that time. In the expected case, this will have had
     * any fetched transformations up to the requested epoch applied. If the fetch was unsuccessful (e.g. because the
     * peer was unavailable) it will still be whatever the currently published metadata, but which entries have been
     * enacted cannot be guaranteed.
     * @param from peer to request log entries from
     * @param awaitAtLeast the upper epoch required. It's expected that the peer is able to supply log entries up to at
     *                     least this epoch.
     * @return The current ClusterMetadata at the time of completion
     */
    public ClusterMetadata fetchLogFromPeer(ClusterMetadata metadata, InetAddressAndPort from, Epoch awaitAtLeast)
    {
        if (FBUtilities.getBroadcastAddressAndPort().equals(from) ||
            !metadata.directory.version(metadata.directory.peerId(from)).isUpgraded())
            return ClusterMetadata.current();
        logger.info("Fetching log from {}, at least {}", from, awaitAtLeast);
        Epoch before = metadata.epoch;
        if (before.isEqualOrAfter(awaitAtLeast))
            return metadata;
        return peerLogFetcher.fetchLogEntriesAndWait(from, awaitAtLeast);
    }

    /**
     * Combines {@link #fetchLogFromPeer} with {@link #fetchLogFromCMS} to synchronously fetch and apply log entries
     * up to the requested epoch. The supplied peer will be contacted first and if after doing so, the current local
     * metadata is not caught up to at least the required epoch, a further request is made to the CMS.
     * The returned ClusterMetadata is guaranteed to have been published, though it may have also been superceded by
     * further updates.
     * If the requested epoch is not reached even after fetching from the CMS, an IllegalStateException is thrown.
     * @param metadata a starting point for the fetch. If the requested epoch is <= the epoch of this metadata, the
     *                 call is a no-op. It's expected that this is usually the current cluster metadata at the time of
     *                 calling.
     * @param from Initial peer to contact. Usually this is the sender of a message containing the requested epoch,
     *             which means it can be assumed that this peer (if available) can supply any missing log entries.
     * @param awaitAtLeast The requested epoch.
     * @return A published ClusterMetadata with all entries up to (at least) the requested epoch enacted.
     * @throws IllegalStateException if the requested epoch could not be reached, even after falling back to CMS catchup
     */
    public ClusterMetadata fetchLogWithFallback(ClusterMetadata metadata, InetAddressAndPort from, Epoch awaitAtLeast)
    {
        Epoch before = metadata.epoch;
        metadata = fetchLogFromPeer(metadata, from, awaitAtLeast);
        if (!metadata.epoch.isEqualOrAfter(awaitAtLeast))
        {
            logger.info("Fetching log from peer didn't reach expected epoch, falling back to CMS");
            ClusterMetadata cmsFetchedMetadata = fetchLogFromCMS();
            if (cmsFetchedMetadata.epoch.isBefore(awaitAtLeast))
                throw new IllegalStateException("Still behind after fetching log from CMS");
            logger.debug("Fetched log from CMS - caught up from epoch {} to epoch {}", before, metadata.epoch);
            return cmsFetchedMetadata;
        }
        logger.debug("Fetched log from {} - caught up from epoch {} to epoch {}", from, before, metadata.epoch);
        return metadata;
    }

    public ClusterMetadata awaitAtLeast(Epoch epoch) throws InterruptedException, TimeoutException
    {
        return log.awaitAtLeast(epoch);
    }

    public MetadataSnapshots snapshotManager()
    {
        return snapshots;
    }

    public ClusterMetadata sealPeriod()
    {
        return ClusterMetadataService.instance.commit(SealPeriod.instance,
                                                      (ClusterMetadata metadata) -> metadata.lastInPeriod,
                                                      (ClusterMetadata metadata) -> metadata,
                                                      (metadata, code, reason) -> {
                                                          // If the transformation got rejected, someone else has beat us to seal this period
                                                          return metadata;
                                                      });
    }

    public void initRecentlySealedPeriodsIndex()
    {
        Sealed.initIndexFromSystemTables();
    }

    public boolean isMigrating()
    {
        return Election.instance.isMigrating();
    }

    public void migrated()
    {
        Election.instance.migrated();
    }
    public void pauseCommits()
    {
        commitsPaused.set(true);
    }

    public void resumeCommits()
    {
        commitsPaused.set(false);
    }

    public boolean commitsPaused()
    {
        return commitsPaused.get();
    }
    /**
     * Switchable implementation that allow us to go between local and remote implementation whenever we need it.
     * When the node becomes a member of CMS, it switches back to being a regular member of a cluster, and all
     * the CMS handlers get disabled.
     */
    @VisibleForTesting
    public static class SwitchableProcessor implements Processor
    {
        private final Processor local;
        private final RemoteProcessor remote;
        private final GossipProcessor gossip;
        private final Supplier<State> cmsStateSupplier;
        private final Commit.Replicator replicator;

        SwitchableProcessor(Processor local,
                            RemoteProcessor remote,
                            GossipProcessor gossip,
                            Commit.Replicator replicator,
                            Supplier<State> cmsStateSupplier)
        {
            this.local = local;
            this.remote = remote;
            this.gossip = gossip;
            this.replicator = replicator;
            this.cmsStateSupplier = cmsStateSupplier;
        }

        @VisibleForTesting
        public Processor delegate()
        {
            return delegateInternal().right;
        }

        private Pair<State, Processor> delegateInternal()
        {
            State state = cmsStateSupplier.get();
            switch (state)
            {
                case LOCAL:
                case RESET:
                    return Pair.create(state, local);
                case REMOTE:
                    return Pair.create(state, remote);
                case GOSSIP:
                    return Pair.create(state, gossip);
            }
            throw new IllegalStateException("Bad CMS state: " + state);
        }

        @Override
        public Commit.Result commit(Entry.Id entryId, Transformation transform, Epoch lastKnown)
        {
            Pair<State, Processor> delegate = delegateInternal();
            Commit.Result result = delegate.right.commit(entryId, transform, lastKnown);
            if (delegate.left == LOCAL || delegate.left == RESET)
                replicator.send(result, null);
            return result;
        }

        public ClusterMetadata fetchLogAndWait()
        {
            return delegate().fetchLogAndWait();
        }

        public String toString()
        {
            return "SwitchableProcessor{" +
                   delegate() + '}';
        }
    }

    public enum State
    {
        LOCAL, REMOTE, GOSSIP, RESET
    }
}
