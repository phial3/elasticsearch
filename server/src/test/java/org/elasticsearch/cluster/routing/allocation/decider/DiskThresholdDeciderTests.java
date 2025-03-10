/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.cluster.routing.allocation.decider;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterInfo;
import org.elasticsearch.cluster.ClusterInfoService;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.DiskUsage;
import org.elasticsearch.cluster.ESAllocationTestCase;
import org.elasticsearch.cluster.RestoreInProgress;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodeRole;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.AllocationId;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.routing.IndexShardRoutingTable;
import org.elasticsearch.cluster.routing.RecoverySource;
import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.cluster.routing.RoutingNodes;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.routing.TestShardRouting;
import org.elasticsearch.cluster.routing.UnassignedInfo.AllocationStatus;
import org.elasticsearch.cluster.routing.UnassignedInfo.Reason;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.cluster.routing.allocation.DiskThresholdSettings;
import org.elasticsearch.cluster.routing.allocation.RoutingAllocation;
import org.elasticsearch.cluster.routing.allocation.allocator.BalancedShardsAllocator;
import org.elasticsearch.cluster.routing.allocation.command.AllocationCommand;
import org.elasticsearch.cluster.routing.allocation.command.AllocationCommands;
import org.elasticsearch.cluster.routing.allocation.command.MoveAllocationCommand;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.repositories.IndexId;
import org.elasticsearch.snapshots.EmptySnapshotsInfoService;
import org.elasticsearch.snapshots.InternalSnapshotsInfoService.SnapshotShard;
import org.elasticsearch.snapshots.Snapshot;
import org.elasticsearch.snapshots.SnapshotId;
import org.elasticsearch.snapshots.SnapshotShardSizeInfo;
import org.elasticsearch.test.gateway.TestGatewayAllocator;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singleton;
import static org.elasticsearch.cluster.routing.RoutingNodesHelper.shardsWithState;
import static org.elasticsearch.cluster.routing.ShardRoutingState.INITIALIZING;
import static org.elasticsearch.cluster.routing.ShardRoutingState.RELOCATING;
import static org.elasticsearch.cluster.routing.ShardRoutingState.STARTED;
import static org.elasticsearch.cluster.routing.ShardRoutingState.UNASSIGNED;
import static org.elasticsearch.cluster.routing.allocation.decider.EnableAllocationDecider.CLUSTER_ROUTING_REBALANCE_ENABLE_SETTING;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.oneOf;

public class DiskThresholdDeciderTests extends ESAllocationTestCase {

    DiskThresholdDecider makeDecider(Settings settings) {
        return new DiskThresholdDecider(settings, new ClusterSettings(settings, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS));
    }

    public void testDiskThreshold() {
        Settings diskSettings = Settings.builder()
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_THRESHOLD_ENABLED_SETTING.getKey(), true)
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), 0.7)
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), 0.8)
            .build();

        ImmutableOpenMap.Builder<String, DiskUsage> usagesBuilder = ImmutableOpenMap.builder();
        usagesBuilder.put("node1", new DiskUsage("node1", "node1", "/dev/null", 100, 10)); // 90% used
        usagesBuilder.put("node2", new DiskUsage("node2", "node2", "/dev/null", 100, 35)); // 65% used
        usagesBuilder.put("node3", new DiskUsage("node3", "node3", "/dev/null", 100, 60)); // 40% used
        usagesBuilder.put("node4", new DiskUsage("node4", "node4", "/dev/null", 100, 80)); // 20% used
        ImmutableOpenMap<String, DiskUsage> usages = usagesBuilder.build();

        ImmutableOpenMap.Builder<String, Long> shardSizesBuilder = ImmutableOpenMap.builder();
        shardSizesBuilder.put("[test][0][p]", 10L); // 10 bytes
        shardSizesBuilder.put("[test][0][r]", 10L);
        ImmutableOpenMap<String, Long> shardSizes = shardSizesBuilder.build();
        final ClusterInfo clusterInfo = new DevNullClusterInfo(usages, usages, shardSizes);

        ClusterSettings clusterSettings = new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS);
        AllocationDeciders deciders = new AllocationDeciders(
            new HashSet<>(Arrays.asList(new SameShardAllocationDecider(Settings.EMPTY, clusterSettings), makeDecider(diskSettings)))
        );

        ClusterInfoService cis = () -> {
            logger.info("--> calling fake getClusterInfo");
            return clusterInfo;
        };
        AllocationService strategy = new AllocationService(
            deciders,
            new TestGatewayAllocator(),
            new BalancedShardsAllocator(Settings.EMPTY),
            cis,
            EmptySnapshotsInfoService.INSTANCE
        );

        Metadata metadata = Metadata.builder()
            .put(IndexMetadata.builder("test").settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(1))
            .build();

        final RoutingTable initialRoutingTable = RoutingTable.builder().addAsNew(metadata.index("test")).build();

        ClusterState clusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .metadata(metadata)
            .routingTable(initialRoutingTable)
            .build();

        logger.info("--> adding two nodes");
        clusterState = ClusterState.builder(clusterState)
            .nodes(DiscoveryNodes.builder().add(newNode("node1")).add(newNode("node2")))
            .build();
        clusterState = strategy.reroute(clusterState, "reroute");
        logShardStates(clusterState);

        // Primary shard should be initializing, replica should not
        assertThat(shardsWithState(clusterState.getRoutingNodes(), INITIALIZING).size(), equalTo(1));

        logger.info("--> start the shards (primaries)");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        logShardStates(clusterState);
        // Assert that we're able to start the primary
        assertThat(shardsWithState(clusterState.getRoutingNodes(), ShardRoutingState.STARTED).size(), equalTo(1));
        // Assert that node1 didn't get any shards because its disk usage is too high
        assertThat(clusterState.getRoutingNodes().node("node1").size(), equalTo(0));

        logger.info("--> start the shards (replicas)");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        logShardStates(clusterState);
        // Assert that the replica couldn't be started since node1 doesn't have enough space
        assertThat(shardsWithState(clusterState.getRoutingNodes(), ShardRoutingState.STARTED).size(), equalTo(1));

        logger.info("--> adding node3");

        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes()).add(newNode("node3"))).build();
        clusterState = strategy.reroute(clusterState, "reroute");

        logShardStates(clusterState);
        // Assert that the replica is initialized now that node3 is available with enough space
        assertThat(shardsWithState(clusterState.getRoutingNodes(), ShardRoutingState.STARTED).size(), equalTo(1));
        assertThat(shardsWithState(clusterState.getRoutingNodes(), ShardRoutingState.INITIALIZING).size(), equalTo(1));

        logger.info("--> start the shards (replicas)");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        logShardStates(clusterState);
        // Assert that the replica couldn't be started since node1 doesn't have enough space
        assertThat(shardsWithState(clusterState.getRoutingNodes(), ShardRoutingState.STARTED).size(), equalTo(2));
        assertThat(clusterState.getRoutingNodes().node("node1").size(), equalTo(0));
        assertThat(clusterState.getRoutingNodes().node("node2").size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node3").size(), equalTo(1));

        logger.info("--> changing decider settings");

        // Set the low threshold to 60 instead of 70
        // Set the high threshold to 70 instead of 80
        // node2 now should not have new shards allocated to it, but shards can remain
        diskSettings = Settings.builder()
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_THRESHOLD_ENABLED_SETTING.getKey(), true)
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), "60%")
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), 0.7)
            .build();

        deciders = new AllocationDeciders(
            new HashSet<>(Arrays.asList(new SameShardAllocationDecider(Settings.EMPTY, clusterSettings), makeDecider(diskSettings)))
        );

        strategy = new AllocationService(
            deciders,
            new TestGatewayAllocator(),
            new BalancedShardsAllocator(Settings.EMPTY),
            cis,
            EmptySnapshotsInfoService.INSTANCE
        );

        clusterState = strategy.reroute(clusterState, "reroute");
        logShardStates(clusterState);

        // Shards remain started
        assertThat(shardsWithState(clusterState.getRoutingNodes(), STARTED).size(), equalTo(2));
        assertThat(clusterState.getRoutingNodes().node("node1").size(), equalTo(0));
        assertThat(clusterState.getRoutingNodes().node("node2").size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node3").size(), equalTo(1));

        logger.info("--> changing settings again");

        // Set the low threshold to 50 instead of 60
        // Set the high threshold to 60 instead of 70
        // node2 now should not have new shards allocated to it, and shards cannot remain
        diskSettings = Settings.builder()
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_THRESHOLD_ENABLED_SETTING.getKey(), true)
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), 0.5)
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), 0.6)
            .build();

        deciders = new AllocationDeciders(
            new HashSet<>(Arrays.asList(new SameShardAllocationDecider(Settings.EMPTY, clusterSettings), makeDecider(diskSettings)))
        );

        strategy = new AllocationService(
            deciders,
            new TestGatewayAllocator(),
            new BalancedShardsAllocator(Settings.EMPTY),
            cis,
            EmptySnapshotsInfoService.INSTANCE
        );

        clusterState = strategy.reroute(clusterState, "reroute");

        logShardStates(clusterState);
        // Shards remain started
        assertThat(shardsWithState(clusterState.getRoutingNodes(), STARTED).size(), equalTo(2));
        assertThat(clusterState.getRoutingNodes().node("node1").size(), equalTo(0));
        // Shard hasn't been moved off of node2 yet because there's nowhere for it to go
        assertThat(clusterState.getRoutingNodes().node("node2").size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node3").size(), equalTo(1));

        logger.info("--> adding node4");

        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes()).add(newNode("node4"))).build();
        clusterState = strategy.reroute(clusterState, "reroute");

        logShardStates(clusterState);
        // Shards remain started
        assertThat(shardsWithState(clusterState.getRoutingNodes(), STARTED).size(), equalTo(1));
        assertThat(shardsWithState(clusterState.getRoutingNodes(), INITIALIZING).size(), equalTo(1));

        logger.info("--> apply INITIALIZING shards");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        logShardStates(clusterState);
        assertThat(clusterState.getRoutingNodes().node("node1").size(), equalTo(0));
        // Node4 is available now, so the shard is moved off of node2
        assertThat(clusterState.getRoutingNodes().node("node2").size(), equalTo(0));
        assertThat(clusterState.getRoutingNodes().node("node3").size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node4").size(), equalTo(1));
    }

    public void testDiskThresholdWithAbsoluteSizes() {
        Settings diskSettings = Settings.builder()
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_THRESHOLD_ENABLED_SETTING.getKey(), true)
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), "30b")
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), "9b")
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_FLOOD_STAGE_WATERMARK_SETTING.getKey(), "5b")
            .build();

        ImmutableOpenMap.Builder<String, DiskUsage> usagesBuilder = ImmutableOpenMap.builder();
        usagesBuilder.put("node1", new DiskUsage("node1", "n1", "/dev/null", 100, 10)); // 90% used
        usagesBuilder.put("node2", new DiskUsage("node2", "n2", "/dev/null", 100, 10)); // 90% used
        usagesBuilder.put("node3", new DiskUsage("node3", "n3", "/dev/null", 100, 60)); // 40% used
        usagesBuilder.put("node4", new DiskUsage("node4", "n4", "/dev/null", 100, 80)); // 20% used
        usagesBuilder.put("node5", new DiskUsage("node5", "n5", "/dev/null", 100, 85)); // 15% used
        ImmutableOpenMap<String, DiskUsage> usages = usagesBuilder.build();

        ImmutableOpenMap.Builder<String, Long> shardSizesBuilder = ImmutableOpenMap.builder();
        shardSizesBuilder.put("[test][0][p]", 10L); // 10 bytes
        shardSizesBuilder.put("[test][0][r]", 10L);
        ImmutableOpenMap<String, Long> shardSizes = shardSizesBuilder.build();
        final ClusterInfo clusterInfo = new DevNullClusterInfo(usages, usages, shardSizes);

        ClusterSettings clusterSettings = new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS);
        AllocationDeciders deciders = new AllocationDeciders(
            new HashSet<>(Arrays.asList(new SameShardAllocationDecider(Settings.EMPTY, clusterSettings), makeDecider(diskSettings)))
        );

        ClusterInfoService cis = () -> {
            logger.info("--> calling fake getClusterInfo");
            return clusterInfo;
        };

        AllocationService strategy = new AllocationService(
            deciders,
            new TestGatewayAllocator(),
            new BalancedShardsAllocator(Settings.EMPTY),
            cis,
            EmptySnapshotsInfoService.INSTANCE
        );

        Metadata metadata = Metadata.builder()
            .put(IndexMetadata.builder("test").settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(2))
            .build();

        RoutingTable initialRoutingTable = RoutingTable.builder().addAsNew(metadata.index("test")).build();

        ClusterState clusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .metadata(metadata)
            .routingTable(initialRoutingTable)
            .build();

        logger.info("--> adding node1 and node2 node");
        clusterState = ClusterState.builder(clusterState)
            .nodes(DiscoveryNodes.builder().add(newNode("node1")).add(newNode("node2")))
            .build();

        clusterState = strategy.reroute(clusterState, "reroute");
        logShardStates(clusterState);

        // Primary should initialize, even though both nodes are over the limit initialize
        assertThat(shardsWithState(clusterState.getRoutingNodes(), INITIALIZING).size(), equalTo(1));

        String nodeWithPrimary, nodeWithoutPrimary;
        if (clusterState.getRoutingNodes().node("node1").size() == 1) {
            nodeWithPrimary = "node1";
            nodeWithoutPrimary = "node2";
        } else {
            nodeWithPrimary = "node2";
            nodeWithoutPrimary = "node1";
        }
        logger.info("--> nodeWithPrimary: {}", nodeWithPrimary);
        logger.info("--> nodeWithoutPrimary: {}", nodeWithoutPrimary);

        // Make node without the primary now habitable to replicas
        usagesBuilder = ImmutableOpenMap.builder(usages);
        usagesBuilder.put(nodeWithoutPrimary, new DiskUsage(nodeWithoutPrimary, "", "/dev/null", 100, 35)); // 65% used
        usages = usagesBuilder.build();
        final ClusterInfo clusterInfo2 = new DevNullClusterInfo(usages, usages, shardSizes);
        cis = () -> {
            logger.info("--> calling fake getClusterInfo");
            return clusterInfo2;
        };
        strategy = new AllocationService(
            deciders,
            new TestGatewayAllocator(),
            new BalancedShardsAllocator(Settings.EMPTY),
            cis,
            EmptySnapshotsInfoService.INSTANCE
        );

        clusterState = strategy.reroute(clusterState, "reroute");
        logShardStates(clusterState);

        // Now the replica should be able to initialize
        assertThat(shardsWithState(clusterState.getRoutingNodes(), INITIALIZING).size(), equalTo(2));

        logger.info("--> start the shards (primaries)");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        logShardStates(clusterState);
        // Assert that we're able to start the primary and replica, since they were both initializing
        assertThat(shardsWithState(clusterState.getRoutingNodes(), ShardRoutingState.STARTED).size(), equalTo(2));
        // Assert that node1 got a single shard (the primary), even though its disk usage is too high
        assertThat(clusterState.getRoutingNodes().node("node1").size(), equalTo(1));
        // Assert that node2 got a single shard (a replica)
        assertThat(clusterState.getRoutingNodes().node("node2").size(), equalTo(1));

        // Assert that one replica is still unassigned
        // assertThat(clusterState.routingNodes().shardsWithState(ShardRoutingState.UNASSIGNED).size(), equalTo(1));

        logger.info("--> adding node3");

        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes()).add(newNode("node3"))).build();
        clusterState = strategy.reroute(clusterState, "reroute");

        logShardStates(clusterState);
        // Assert that the replica is initialized now that node3 is available with enough space
        assertThat(shardsWithState(clusterState.getRoutingNodes(), ShardRoutingState.STARTED).size(), equalTo(2));
        assertThat(shardsWithState(clusterState.getRoutingNodes(), ShardRoutingState.INITIALIZING).size(), equalTo(1));

        logger.info("--> start the shards (replicas)");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        logShardStates(clusterState);
        // Assert that all replicas could be started
        assertThat(shardsWithState(clusterState.getRoutingNodes(), ShardRoutingState.STARTED).size(), equalTo(3));
        assertThat(clusterState.getRoutingNodes().node("node1").size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node2").size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node3").size(), equalTo(1));

        logger.info("--> changing decider settings");

        // Set the low threshold to 60 instead of 70
        // Set the high threshold to 70 instead of 80
        // node2 now should not have new shards allocated to it, but shards can remain
        diskSettings = Settings.builder()
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_THRESHOLD_ENABLED_SETTING.getKey(), true)
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), "40b")
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), "30b")
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_FLOOD_STAGE_WATERMARK_SETTING.getKey(), "20b")
            .build();

        deciders = new AllocationDeciders(
            new HashSet<>(Arrays.asList(new SameShardAllocationDecider(Settings.EMPTY, clusterSettings), makeDecider(diskSettings)))
        );

        strategy = new AllocationService(
            deciders,
            new TestGatewayAllocator(),
            new BalancedShardsAllocator(Settings.EMPTY),
            cis,
            EmptySnapshotsInfoService.INSTANCE
        );

        clusterState = strategy.reroute(clusterState, "reroute");
        logShardStates(clusterState);

        // Shards remain started
        assertThat(shardsWithState(clusterState.getRoutingNodes(), STARTED).size(), equalTo(3));
        assertThat(clusterState.getRoutingNodes().node("node1").size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node2").size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node3").size(), equalTo(1));

        logger.info("--> changing settings again");

        // Set the low threshold to 50 instead of 60
        // Set the high threshold to 60 instead of 70
        // node2 now should not have new shards allocated to it, and shards cannot remain
        diskSettings = Settings.builder()
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_THRESHOLD_ENABLED_SETTING.getKey(), true)
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), "50b")
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), "40b")
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_FLOOD_STAGE_WATERMARK_SETTING.getKey(), "30b")
            .build();

        deciders = new AllocationDeciders(
            new HashSet<>(Arrays.asList(new SameShardAllocationDecider(Settings.EMPTY, clusterSettings), makeDecider(diskSettings)))
        );

        strategy = new AllocationService(
            deciders,
            new TestGatewayAllocator(),
            new BalancedShardsAllocator(Settings.EMPTY),
            cis,
            EmptySnapshotsInfoService.INSTANCE
        );

        clusterState = strategy.reroute(clusterState, "reroute");

        logShardStates(clusterState);
        // Shards remain started
        assertThat(shardsWithState(clusterState.getRoutingNodes(), STARTED).size(), equalTo(3));
        assertThat(clusterState.getRoutingNodes().node("node1").size(), equalTo(1));
        // Shard hasn't been moved off of node2 yet because there's nowhere for it to go
        assertThat(clusterState.getRoutingNodes().node("node2").size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node3").size(), equalTo(1));

        logger.info("--> adding node4");

        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes()).add(newNode("node4"))).build();
        clusterState = strategy.reroute(clusterState, "reroute");

        logShardStates(clusterState);
        // Shards remain started
        assertThat(shardsWithState(clusterState.getRoutingNodes(), STARTED).size(), equalTo(2));
        // One shard is relocating off of node1
        assertThat(shardsWithState(clusterState.getRoutingNodes(), RELOCATING).size(), equalTo(1));
        assertThat(shardsWithState(clusterState.getRoutingNodes(), INITIALIZING).size(), equalTo(1));

        logger.info("--> apply INITIALIZING shards");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        logShardStates(clusterState);
        // primary shard already has been relocated away
        assertThat(clusterState.getRoutingNodes().node(nodeWithPrimary).size(), equalTo(0));
        // node with increased space still has its shard
        assertThat(clusterState.getRoutingNodes().node(nodeWithoutPrimary).size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node3").size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node4").size(), equalTo(1));

        logger.info("--> adding node5");

        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes()).add(newNode("node5"))).build();
        clusterState = strategy.reroute(clusterState, "reroute");

        logShardStates(clusterState);
        // Shards remain started on node3 and node4
        assertThat(shardsWithState(clusterState.getRoutingNodes(), STARTED).size(), equalTo(2));
        // One shard is relocating off of node2 now
        assertThat(shardsWithState(clusterState.getRoutingNodes(), RELOCATING).size(), equalTo(1));
        // Initializing on node5
        assertThat(shardsWithState(clusterState.getRoutingNodes(), INITIALIZING).size(), equalTo(1));

        logger.info("--> apply INITIALIZING shards");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        logger.info("--> final cluster state:");
        logShardStates(clusterState);
        // Node1 still has no shards because it has no space for them
        assertThat(clusterState.getRoutingNodes().node("node1").size(), equalTo(0));
        // Node5 is available now, so the shard is moved off of node2
        assertThat(clusterState.getRoutingNodes().node("node2").size(), equalTo(0));
        assertThat(clusterState.getRoutingNodes().node("node3").size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node4").size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node5").size(), equalTo(1));
    }

    public void testDiskThresholdWithShardSizes() {
        Settings diskSettings = Settings.builder()
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_THRESHOLD_ENABLED_SETTING.getKey(), true)
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), 0.7)
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), "71%")
            .build();

        ImmutableOpenMap.Builder<String, DiskUsage> usagesBuilder = ImmutableOpenMap.builder();
        usagesBuilder.put("node1", new DiskUsage("node1", "n1", "/dev/null", 100, 31)); // 69% used
        usagesBuilder.put("node2", new DiskUsage("node2", "n2", "/dev/null", 100, 1));  // 99% used
        ImmutableOpenMap<String, DiskUsage> usages = usagesBuilder.build();

        ImmutableOpenMap.Builder<String, Long> shardSizesBuilder = ImmutableOpenMap.builder();
        shardSizesBuilder.put("[test][0][p]", 10L); // 10 bytes
        ImmutableOpenMap<String, Long> shardSizes = shardSizesBuilder.build();
        final ClusterInfo clusterInfo = new DevNullClusterInfo(usages, usages, shardSizes);

        AllocationDeciders deciders = new AllocationDeciders(
            new HashSet<>(
                Arrays.asList(
                    new SameShardAllocationDecider(
                        Settings.EMPTY,
                        new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS)
                    ),
                    makeDecider(diskSettings)
                )
            )
        );

        ClusterInfoService cis = () -> {
            logger.info("--> calling fake getClusterInfo");
            return clusterInfo;
        };

        AllocationService strategy = new AllocationService(
            deciders,
            new TestGatewayAllocator(),
            new BalancedShardsAllocator(Settings.EMPTY),
            cis,
            EmptySnapshotsInfoService.INSTANCE
        );

        Metadata metadata = Metadata.builder()
            .put(IndexMetadata.builder("test").settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(0))
            .build();

        RoutingTable routingTable = RoutingTable.builder().addAsNew(metadata.index("test")).build();

        ClusterState clusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .metadata(metadata)
            .routingTable(routingTable)
            .build();
        logger.info("--> adding node1");
        clusterState = ClusterState.builder(clusterState)
            .nodes(
                DiscoveryNodes.builder().add(newNode("node1")).add(newNode("node2")) // node2 is added because DiskThresholdDecider
                                                                                     // automatically ignore single-node clusters
            )
            .build();
        routingTable = strategy.reroute(clusterState, "reroute").routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();
        logger.info("--> start the shards (primaries)");
        routingTable = startInitializingShardsAndReroute(strategy, clusterState).routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();
        logShardStates(clusterState);

        // Shard can't be allocated to node1 (or node2) because it would cause too much usage
        assertThat(shardsWithState(clusterState.getRoutingNodes(), INITIALIZING).size(), equalTo(0));
        // No shards are started, no nodes have enough disk for allocation
        assertThat(shardsWithState(clusterState.getRoutingNodes(), STARTED).size(), equalTo(0));
    }

    public void testUnknownDiskUsage() {
        Settings diskSettings = Settings.builder()
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_THRESHOLD_ENABLED_SETTING.getKey(), true)
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), 0.7)
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), 0.85)
            .build();

        ImmutableOpenMap.Builder<String, DiskUsage> usagesBuilder = ImmutableOpenMap.builder();
        usagesBuilder.put("node2", new DiskUsage("node2", "node2", "/dev/null", 100, 50)); // 50% used
        usagesBuilder.put("node3", new DiskUsage("node3", "node3", "/dev/null", 100, 0));  // 100% used
        ImmutableOpenMap<String, DiskUsage> usages = usagesBuilder.build();

        ImmutableOpenMap.Builder<String, Long> shardSizesBuilder = ImmutableOpenMap.builder();
        shardSizesBuilder.put("[test][0][p]", 10L); // 10 bytes
        shardSizesBuilder.put("[test][0][r]", 10L); // 10 bytes
        ImmutableOpenMap<String, Long> shardSizes = shardSizesBuilder.build();
        final ClusterInfo clusterInfo = new DevNullClusterInfo(usages, usages, shardSizes);

        AllocationDeciders deciders = new AllocationDeciders(
            new HashSet<>(
                Arrays.asList(
                    new SameShardAllocationDecider(
                        Settings.EMPTY,
                        new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS)
                    ),
                    makeDecider(diskSettings)
                )
            )
        );

        ClusterInfoService cis = () -> {
            logger.info("--> calling fake getClusterInfo");
            return clusterInfo;
        };

        AllocationService strategy = new AllocationService(
            deciders,
            new TestGatewayAllocator(),
            new BalancedShardsAllocator(Settings.EMPTY),
            cis,
            EmptySnapshotsInfoService.INSTANCE
        );

        Metadata metadata = Metadata.builder()
            .put(IndexMetadata.builder("test").settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(0))
            .build();

        RoutingTable routingTable = RoutingTable.builder().addAsNew(metadata.index("test")).build();

        ClusterState clusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .metadata(metadata)
            .routingTable(routingTable)
            .build();
        logger.info("--> adding node1");
        clusterState = ClusterState.builder(clusterState)
            .nodes(
                DiscoveryNodes.builder().add(newNode("node1")).add(newNode("node3")) // node3 is added because DiskThresholdDecider
                                                                                     // automatically ignore single-node clusters
            )
            .build();
        routingTable = strategy.reroute(clusterState, "reroute").routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();

        // Shard can be allocated to node1, even though it only has 25% free,
        // because it's a primary that's never been allocated before
        assertThat(shardsWithState(clusterState.getRoutingNodes(), INITIALIZING).size(), equalTo(1));

        logger.info("--> start the shards (primaries)");
        routingTable = startInitializingShardsAndReroute(strategy, clusterState).routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();
        logShardStates(clusterState);

        // A single shard is started on node1, even though it normally would not
        // be allowed, because it's a primary that hasn't been allocated, and node1
        // is still below the high watermark (unlike node3)
        assertThat(shardsWithState(clusterState.getRoutingNodes(), STARTED).size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node1").size(), equalTo(1));
    }

    public void testAverageUsage() {
        RoutingNode rn = new RoutingNode("node1", newNode("node1"));
        DiskThresholdDecider decider = makeDecider(Settings.EMPTY);

        ImmutableOpenMap.Builder<String, DiskUsage> usages = ImmutableOpenMap.builder();
        usages.put("node2", new DiskUsage("node2", "n2", "/dev/null", 100, 50)); // 50% used
        usages.put("node3", new DiskUsage("node3", "n3", "/dev/null", 100, 0));  // 100% used

        DiskUsage node1Usage = decider.averageUsage(rn, usages.build());
        assertThat(node1Usage.getTotalBytes(), equalTo(100L));
        assertThat(node1Usage.getFreeBytes(), equalTo(25L));
    }

    public void testFreeDiskPercentageAfterShardAssigned() {
        DiskThresholdDecider decider = makeDecider(Settings.EMPTY);

        Map<String, DiskUsage> usages = new HashMap<>();
        usages.put("node2", new DiskUsage("node2", "n2", "/dev/null", 100, 50)); // 50% used
        usages.put("node3", new DiskUsage("node3", "n3", "/dev/null", 100, 0));  // 100% used

        Double after = decider.freeDiskPercentageAfterShardAssigned(
            new DiskThresholdDecider.DiskUsageWithRelocations(new DiskUsage("node2", "n2", "/dev/null", 100, 30), 0L),
            11L
        );
        assertThat(after, equalTo(19.0));
    }

    public void testShardRelocationsTakenIntoAccount() {
        Settings diskSettings = Settings.builder()
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_THRESHOLD_ENABLED_SETTING.getKey(), true)
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), 0.7)
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), 0.8)
            .build();

        ImmutableOpenMap.Builder<String, DiskUsage> usagesBuilder = ImmutableOpenMap.builder();
        usagesBuilder.put("node1", new DiskUsage("node1", "n1", "/dev/null", 100, 40)); // 60% used
        usagesBuilder.put("node2", new DiskUsage("node2", "n2", "/dev/null", 100, 40)); // 60% used
        usagesBuilder.put("node3", new DiskUsage("node3", "n3", "/dev/null", 100, 40)); // 60% used
        ImmutableOpenMap<String, DiskUsage> usages = usagesBuilder.build();

        ImmutableOpenMap.Builder<String, Long> shardSizesBuilder = ImmutableOpenMap.builder();
        shardSizesBuilder.put("[test][0][p]", 14L); // 14 bytes
        shardSizesBuilder.put("[test][0][r]", 14L);
        shardSizesBuilder.put("[test2][0][p]", 1L); // 1 bytes
        shardSizesBuilder.put("[test2][0][r]", 1L);
        ImmutableOpenMap<String, Long> shardSizes = shardSizesBuilder.build();
        final ClusterInfo clusterInfo = new DevNullClusterInfo(usages, usages, shardSizes);

        DiskThresholdDecider decider = makeDecider(diskSettings);
        final ClusterSettings clusterSettings = new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS);
        AllocationDeciders deciders = new AllocationDeciders(
            new HashSet<>(
                Arrays.asList(
                    new SameShardAllocationDecider(Settings.EMPTY, clusterSettings),
                    new EnableAllocationDecider(
                        Settings.builder().put(CLUSTER_ROUTING_REBALANCE_ENABLE_SETTING.getKey(), "none").build(),
                        clusterSettings
                    ),
                    decider
                )
            )
        );

        final AtomicReference<ClusterInfo> clusterInfoReference = new AtomicReference<>(clusterInfo);
        final ClusterInfoService cis = clusterInfoReference::get;

        AllocationService strategy = new AllocationService(
            deciders,
            new TestGatewayAllocator(),
            new BalancedShardsAllocator(Settings.EMPTY),
            cis,
            EmptySnapshotsInfoService.INSTANCE
        );

        Metadata metadata = Metadata.builder()
            .put(IndexMetadata.builder("test").settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(1))
            .put(IndexMetadata.builder("test2").settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(1))
            .build();

        RoutingTable initialRoutingTable = RoutingTable.builder()
            .addAsNew(metadata.index("test"))
            .addAsNew(metadata.index("test2"))
            .build();

        ClusterState clusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .metadata(metadata)
            .routingTable(initialRoutingTable)
            .build();

        logger.info("--> adding two nodes");
        clusterState = ClusterState.builder(clusterState)
            .nodes(DiscoveryNodes.builder().add(newNode("node1")).add(newNode("node2")))
            .build();
        clusterState = strategy.reroute(clusterState, "reroute");
        logShardStates(clusterState);

        // shards should be initializing
        assertThat(shardsWithState(clusterState.getRoutingNodes(), INITIALIZING).size(), equalTo(4));

        logger.info("--> start the shards");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        logShardStates(clusterState);
        // Assert that we're able to start the primary and replicas
        assertThat(shardsWithState(clusterState.getRoutingNodes(), ShardRoutingState.STARTED).size(), equalTo(4));

        logger.info("--> adding node3");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes()).add(newNode("node3"))).build();

        {
            AllocationCommand moveAllocationCommand = new MoveAllocationCommand("test", 0, "node2", "node3");
            AllocationCommands cmds = new AllocationCommands(moveAllocationCommand);

            clusterState = strategy.reroute(clusterState, cmds, false, false).getClusterState();
            logShardStates(clusterState);
        }

        final ImmutableOpenMap.Builder<String, DiskUsage> overfullUsagesBuilder = ImmutableOpenMap.builder();
        overfullUsagesBuilder.put("node1", new DiskUsage("node1", "n1", "/dev/null", 100, 40)); // 60% used
        overfullUsagesBuilder.put("node2", new DiskUsage("node2", "n2", "/dev/null", 100, 40)); // 60% used
        overfullUsagesBuilder.put("node3", new DiskUsage("node3", "n3", "/dev/null", 100, 0));  // 100% used
        final ImmutableOpenMap<String, DiskUsage> overfullUsages = overfullUsagesBuilder.build();

        final ImmutableOpenMap.Builder<String, Long> largerShardSizesBuilder = ImmutableOpenMap.builder();
        largerShardSizesBuilder.put("[test][0][p]", 14L);
        largerShardSizesBuilder.put("[test][0][r]", 14L);
        largerShardSizesBuilder.put("[test2][0][p]", 2L);
        largerShardSizesBuilder.put("[test2][0][r]", 2L);
        final ImmutableOpenMap<String, Long> largerShardSizes = largerShardSizesBuilder.build();

        final ClusterInfo overfullClusterInfo = new DevNullClusterInfo(overfullUsages, overfullUsages, largerShardSizes);

        {
            AllocationCommand moveAllocationCommand = new MoveAllocationCommand("test2", 0, "node2", "node3");
            AllocationCommands cmds = new AllocationCommands(moveAllocationCommand);

            final ClusterState clusterStateThatRejectsCommands = clusterState;

            assertThat(
                expectThrows(IllegalArgumentException.class, () -> strategy.reroute(clusterStateThatRejectsCommands, cmds, false, false))
                    .getMessage(),
                containsString(
                    "the node is above the low watermark cluster setting "
                        + "[cluster.routing.allocation.disk.watermark.low=0.7], using more disk space than the maximum "
                        + "allowed [70.0%], actual free: [26.0%]"
                )
            );

            clusterInfoReference.set(overfullClusterInfo);

            assertThat(
                expectThrows(IllegalArgumentException.class, () -> strategy.reroute(clusterStateThatRejectsCommands, cmds, false, false))
                    .getMessage(),
                containsString("the node has fewer free bytes remaining than the total size of all incoming shards")
            );

            clusterInfoReference.set(clusterInfo);
        }

        {
            AllocationCommand moveAllocationCommand = new MoveAllocationCommand("test2", 0, "node2", "node3");
            AllocationCommands cmds = new AllocationCommands(moveAllocationCommand);

            clusterState = startInitializingShardsAndReroute(strategy, clusterState);
            clusterState = strategy.reroute(clusterState, cmds, false, false).getClusterState();
            logShardStates(clusterState);

            clusterInfoReference.set(overfullClusterInfo);

            strategy.reroute(clusterState, "foo"); // ensure reroute doesn't fail even though there is negative free space
        }

        {
            clusterInfoReference.set(overfullClusterInfo);
            clusterState = applyStartedShardsUntilNoChange(clusterState, strategy);
            final List<ShardRouting> startedShardsWithOverfullDisk = shardsWithState(clusterState.getRoutingNodes(), STARTED);
            assertThat(startedShardsWithOverfullDisk.size(), equalTo(4));
            for (ShardRouting shardRouting : startedShardsWithOverfullDisk) {
                // no shards on node3 since it has no free space
                assertThat(shardRouting.toString(), shardRouting.currentNodeId(), oneOf("node1", "node2"));
            }

            // reset free space on node 3 and reserve space on node1
            clusterInfoReference.set(
                new DevNullClusterInfo(
                    usages,
                    usages,
                    shardSizes,
                    (new ImmutableOpenMap.Builder<ClusterInfo.NodeAndPath, ClusterInfo.ReservedSpace>()).fPut(
                        new ClusterInfo.NodeAndPath("node1", "/dev/null"),
                        new ClusterInfo.ReservedSpace.Builder().add(new ShardId("", "", 0), between(51, 200)).build()
                    ).build()
                )
            );
            clusterState = applyStartedShardsUntilNoChange(clusterState, strategy);
            final List<ShardRouting> startedShardsWithReservedSpace = shardsWithState(clusterState.getRoutingNodes(), STARTED);
            assertThat(startedShardsWithReservedSpace.size(), equalTo(4));
            for (ShardRouting shardRouting : startedShardsWithReservedSpace) {
                // no shards on node1 since all its free space is reserved
                assertThat(shardRouting.toString(), shardRouting.currentNodeId(), oneOf("node2", "node3"));
            }
        }
    }

    public void testCanRemainWithShardRelocatingAway() {
        Settings diskSettings = Settings.builder()
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_THRESHOLD_ENABLED_SETTING.getKey(), true)
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), "60%")
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), "70%")
            .build();

        // We have an index with 2 primary shards each taking 40 bytes. Each node has 100 bytes available
        ImmutableOpenMap.Builder<String, DiskUsage> usagesBuilder = ImmutableOpenMap.builder();
        usagesBuilder.put("node1", new DiskUsage("node1", "n1", "/dev/null", 100, 20)); // 80% used
        usagesBuilder.put("node2", new DiskUsage("node2", "n2", "/dev/null", 100, 100)); // 0% used
        ImmutableOpenMap<String, DiskUsage> usages = usagesBuilder.build();

        ImmutableOpenMap.Builder<String, Long> shardSizesBuilder = ImmutableOpenMap.builder();
        shardSizesBuilder.put("[test][0][p]", 40L);
        shardSizesBuilder.put("[test][1][p]", 40L);
        shardSizesBuilder.put("[foo][0][p]", 10L);
        ImmutableOpenMap<String, Long> shardSizes = shardSizesBuilder.build();

        final ClusterInfo clusterInfo = new DevNullClusterInfo(usages, usages, shardSizes);

        DiskThresholdDecider diskThresholdDecider = makeDecider(diskSettings);
        Metadata metadata = Metadata.builder()
            .put(IndexMetadata.builder("test").settings(settings(Version.CURRENT)).numberOfShards(2).numberOfReplicas(0))
            .put(IndexMetadata.builder("foo").settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(0))
            .build();

        RoutingTable initialRoutingTable = RoutingTable.builder().addAsNew(metadata.index("test")).addAsNew(metadata.index("foo")).build();

        DiscoveryNode discoveryNode1 = new DiscoveryNode(
            "node1",
            buildNewFakeTransportAddress(),
            emptyMap(),
            MASTER_DATA_ROLES,
            Version.CURRENT
        );
        DiscoveryNode discoveryNode2 = new DiscoveryNode(
            "node2",
            buildNewFakeTransportAddress(),
            emptyMap(),
            MASTER_DATA_ROLES,
            Version.CURRENT
        );
        DiscoveryNodes discoveryNodes = DiscoveryNodes.builder().add(discoveryNode1).add(discoveryNode2).build();

        ClusterState baseClusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .metadata(metadata)
            .routingTable(initialRoutingTable)
            .nodes(discoveryNodes)
            .build();

        // Two shards consuming each 80% of disk space while 70% is allowed, so shard 0 isn't allowed here
        ShardRouting firstRouting = TestShardRouting.newShardRouting("test", 0, "node1", null, true, ShardRoutingState.STARTED);
        ShardRouting secondRouting = TestShardRouting.newShardRouting("test", 1, "node1", null, true, ShardRoutingState.STARTED);
        RoutingNode firstRoutingNode = new RoutingNode("node1", discoveryNode1, firstRouting, secondRouting);
        RoutingTable.Builder builder = RoutingTable.builder()
            .add(
                IndexRoutingTable.builder(firstRouting.index())
                    .addIndexShard(new IndexShardRoutingTable.Builder(firstRouting.shardId()).addShard(firstRouting).build())
                    .addIndexShard(new IndexShardRoutingTable.Builder(secondRouting.shardId()).addShard(secondRouting).build())
            );
        ClusterState clusterState = ClusterState.builder(baseClusterState).routingTable(builder.build()).build();
        RoutingAllocation routingAllocation = new RoutingAllocation(
            null,
            new RoutingNodes(clusterState),
            clusterState,
            clusterInfo,
            null,
            System.nanoTime()
        );
        routingAllocation.debugDecision(true);
        Decision decision = diskThresholdDecider.canRemain(firstRouting, firstRoutingNode, routingAllocation);
        assertThat(decision.type(), equalTo(Decision.Type.NO));
        assertThat(
            ((Decision.Single) decision).getExplanation(),
            containsString(
                "the shard cannot remain on this node because it is above the high watermark cluster setting "
                    + "[cluster.routing.allocation.disk.watermark.high=70%] and there is less than the required [30.0%] free disk on node, "
                    + "actual free: [20.0%]"
            )
        );

        // Two shards consuming each 80% of disk space while 70% is allowed, but one is relocating, so shard 0 can stay
        firstRouting = TestShardRouting.newShardRouting("test", 0, "node1", null, true, ShardRoutingState.STARTED);
        secondRouting = TestShardRouting.newShardRouting("test", 1, "node1", "node2", true, ShardRoutingState.RELOCATING);
        ShardRouting fooRouting = TestShardRouting.newShardRouting("foo", 0, null, true, ShardRoutingState.UNASSIGNED);
        firstRoutingNode = new RoutingNode("node1", discoveryNode1, firstRouting, secondRouting);
        builder = RoutingTable.builder()
            .add(
                IndexRoutingTable.builder(firstRouting.index())
                    .addIndexShard(new IndexShardRoutingTable.Builder(firstRouting.shardId()).addShard(firstRouting).build())
                    .addIndexShard(new IndexShardRoutingTable.Builder(secondRouting.shardId()).addShard(secondRouting).build())
            );
        clusterState = ClusterState.builder(baseClusterState).routingTable(builder.build()).build();
        routingAllocation = new RoutingAllocation(null, new RoutingNodes(clusterState), clusterState, clusterInfo, null, System.nanoTime());
        routingAllocation.debugDecision(true);
        decision = diskThresholdDecider.canRemain(firstRouting, firstRoutingNode, routingAllocation);
        assertThat(decision.type(), equalTo(Decision.Type.YES));
        assertEquals(
            "there is enough disk on this node for the shard to remain, free: [60b]",
            ((Decision.Single) decision).getExplanation()
        );
        decision = diskThresholdDecider.canAllocate(fooRouting, firstRoutingNode, routingAllocation);
        assertThat(decision.type(), equalTo(Decision.Type.NO));
        if (fooRouting.recoverySource().getType() == RecoverySource.Type.EMPTY_STORE) {
            assertThat(
                ((Decision.Single) decision).getExplanation(),
                containsString(
                    "the node is above the high watermark cluster setting [cluster.routing.allocation.disk.watermark.high=70%], using "
                        + "more disk space than the maximum allowed [70.0%], actual free: [20.0%]"
                )
            );
        } else {
            assertThat(
                ((Decision.Single) decision).getExplanation(),
                containsString(
                    "the node is above the low watermark cluster setting [cluster.routing.allocation.disk.watermark.low=60%], using more "
                        + "disk space than the maximum allowed [60.0%], actual free: [20.0%]"
                )
            );
        }

        // Creating AllocationService instance and the services it depends on...
        ClusterInfoService cis = () -> {
            logger.info("--> calling fake getClusterInfo");
            return clusterInfo;
        };
        AllocationDeciders deciders = new AllocationDeciders(
            new HashSet<>(
                Arrays.asList(
                    new SameShardAllocationDecider(
                        Settings.EMPTY,
                        new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS)
                    ),
                    diskThresholdDecider
                )
            )
        );
        AllocationService strategy = new AllocationService(
            deciders,
            new TestGatewayAllocator(),
            new BalancedShardsAllocator(Settings.EMPTY),
            cis,
            EmptySnapshotsInfoService.INSTANCE
        );
        // Ensure that the reroute call doesn't alter the routing table, since the first primary is relocating away
        // and therefor we will have sufficient disk space on node1.
        ClusterState result = strategy.reroute(clusterState, "reroute");
        assertThat(result, equalTo(clusterState));
        assertThat(result.routingTable().index("test").getShards().get(0).primaryShard().state(), equalTo(STARTED));
        assertThat(result.routingTable().index("test").getShards().get(0).primaryShard().currentNodeId(), equalTo("node1"));
        assertThat(result.routingTable().index("test").getShards().get(0).primaryShard().relocatingNodeId(), nullValue());
        assertThat(result.routingTable().index("test").getShards().get(1).primaryShard().state(), equalTo(RELOCATING));
        assertThat(result.routingTable().index("test").getShards().get(1).primaryShard().currentNodeId(), equalTo("node1"));
        assertThat(result.routingTable().index("test").getShards().get(1).primaryShard().relocatingNodeId(), equalTo("node2"));
    }

    public void testWatermarksEnabledForSingleDataNode() {
        Settings.Builder builder = Settings.builder()
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_THRESHOLD_ENABLED_SETTING.getKey(), true)
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), "60%")
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), "70%");
        if (randomBoolean()) {
            builder.put(DiskThresholdDecider.ENABLE_FOR_SINGLE_DATA_NODE.getKey(), true);
        }
        Settings diskSettings = builder.build();

        ImmutableOpenMap.Builder<String, DiskUsage> usagesBuilder = ImmutableOpenMap.builder();
        usagesBuilder.put("data", new DiskUsage("data", "data", "/dev/null", 100, 20));  // 80% used
        ImmutableOpenMap<String, DiskUsage> usages = usagesBuilder.build();

        // We have an index with 1 primary shard, taking 40 bytes. The single data node has only 20 bytes free.
        ImmutableOpenMap.Builder<String, Long> shardSizes = ImmutableOpenMap.builder();
        shardSizes.put("[test][0][p]", 40L);
        final ClusterInfo clusterInfo = new DevNullClusterInfo(usages, usages, shardSizes.build());

        DiskThresholdDecider diskThresholdDecider = makeDecider(diskSettings);
        Metadata metadata = Metadata.builder()
            .put(IndexMetadata.builder("test").settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(0))
            .build();
        RoutingTable initialRoutingTable = RoutingTable.builder().addAsNew(metadata.index("test")).build();

        DiscoveryNode masterNode = new DiscoveryNode(
            "master",
            "master",
            buildNewFakeTransportAddress(),
            emptyMap(),
            singleton(DiscoveryNodeRole.MASTER_ROLE),
            Version.CURRENT
        );
        DiscoveryNode dataNode = new DiscoveryNode(
            "data",
            "data",
            buildNewFakeTransportAddress(),
            emptyMap(),
            singleton(DiscoveryNodeRole.DATA_ROLE),
            Version.CURRENT
        );
        DiscoveryNodes.Builder discoveryNodesBuilder = DiscoveryNodes.builder().add(dataNode);
        if (randomBoolean()) {
            discoveryNodesBuilder.add(masterNode);
        }
        DiscoveryNodes discoveryNodes = discoveryNodesBuilder.build();

        ClusterState clusterState = ClusterState.builder(new ClusterName("test"))
            .nodes(discoveryNodes)
            .metadata(metadata)
            .routingTable(initialRoutingTable)
            .build();

        // validate that the shard cannot be allocated
        ClusterInfoService cis = () -> clusterInfo;
        AllocationDeciders deciders = new AllocationDeciders(
            new HashSet<>(
                Arrays.asList(
                    new SameShardAllocationDecider(
                        Settings.EMPTY,
                        new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS)
                    ),
                    diskThresholdDecider
                )
            )
        );
        AllocationService strategy = new AllocationService(
            deciders,
            new TestGatewayAllocator(),
            new BalancedShardsAllocator(Settings.EMPTY),
            cis,
            EmptySnapshotsInfoService.INSTANCE
        );
        ClusterState result = strategy.reroute(clusterState, "reroute");

        ShardRouting shardRouting = result.routingTable().index("test").getShards().get(0).primaryShard();
        assertThat(shardRouting.state(), equalTo(UNASSIGNED));
        assertThat(shardRouting.currentNodeId(), nullValue());
        assertThat(shardRouting.relocatingNodeId(), nullValue());

        // force assign shard and validate that it cannot remain.
        ShardId shardId = shardRouting.shardId();
        ShardRouting startedShard = shardRouting.initialize("data", null, 40L).moveToStarted();
        RoutingTable forceAssignedRoutingTable = RoutingTable.builder()
            .add(
                IndexRoutingTable.builder(shardId.getIndex())
                    .addIndexShard(new IndexShardRoutingTable.Builder(shardId).addShard(startedShard).build())
            )
            .build();
        clusterState = ClusterState.builder(clusterState).routingTable(forceAssignedRoutingTable).build();

        RoutingAllocation routingAllocation = new RoutingAllocation(
            null,
            new RoutingNodes(clusterState),
            clusterState,
            clusterInfo,
            null,
            System.nanoTime()
        );
        routingAllocation.debugDecision(true);
        Decision decision = diskThresholdDecider.canRemain(startedShard, clusterState.getRoutingNodes().node("data"), routingAllocation);
        assertThat(decision.type(), equalTo(Decision.Type.NO));
        assertThat(
            decision.getExplanation(),
            containsString(
                "the shard cannot remain on this node because it is above the high watermark cluster setting"
                    + " [cluster.routing.allocation.disk.watermark.high=70%] and there is less than the required [30.0%] free disk on node,"
                    + " actual free: [20.0%]"
            )
        );

        if (DiskThresholdDecider.ENABLE_FOR_SINGLE_DATA_NODE.exists(diskSettings)) {
            assertSettingDeprecationsAndWarnings(new Setting<?>[] { DiskThresholdDecider.ENABLE_FOR_SINGLE_DATA_NODE });
        }
    }

    public void testSingleDataNodeDeprecationWarning() {
        Settings settings = Settings.builder().put(DiskThresholdDecider.ENABLE_FOR_SINGLE_DATA_NODE.getKey(), false).build();

        IllegalArgumentException e = expectThrows(
            IllegalArgumentException.class,
            () -> new DiskThresholdDecider(settings, new ClusterSettings(settings, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS))
        );

        assertThat(
            e.getCause().getMessage(),
            equalTo(
                "setting [cluster.routing.allocation.disk.watermark.enable_for_single_data_node=false] is not allowed,"
                    + " only true is valid"
            )
        );

        assertSettingDeprecationsAndWarnings(new Setting<?>[] { DiskThresholdDecider.ENABLE_FOR_SINGLE_DATA_NODE });
    }

    public void testDiskThresholdWithSnapshotShardSizes() {
        final long shardSizeInBytes = randomBoolean() ? 10L : 50L;
        logger.info("--> using shard size [{}]", shardSizeInBytes);

        final Settings diskSettings = Settings.builder()
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_DISK_THRESHOLD_ENABLED_SETTING.getKey(), true)
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_LOW_DISK_WATERMARK_SETTING.getKey(), "90%")
            .put(DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_HIGH_DISK_WATERMARK_SETTING.getKey(), "95%")
            .build();

        final ImmutableOpenMap.Builder<String, DiskUsage> usagesBuilder = ImmutableOpenMap.builder();
        usagesBuilder.put("node1", new DiskUsage("node1", "n1", "/dev/null", 100, 21));  // 79% used
        usagesBuilder.put("node2", new DiskUsage("node2", "n2", "/dev/null", 100, 1)); // 99% used
        final ImmutableOpenMap<String, DiskUsage> usages = usagesBuilder.build();
        final ClusterInfoService clusterInfoService = () -> new DevNullClusterInfo(usages, usages, ImmutableOpenMap.of());

        final AllocationDeciders deciders = new AllocationDeciders(
            new HashSet<>(
                Arrays.asList(
                    new RestoreInProgressAllocationDecider(),
                    new SameShardAllocationDecider(
                        Settings.EMPTY,
                        new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS)
                    ),
                    makeDecider(diskSettings)
                )
            )
        );

        final Snapshot snapshot = new Snapshot("_repository", new SnapshotId("_snapshot_name", UUIDs.randomBase64UUID(random())));
        final IndexId indexId = new IndexId("_indexid_name", UUIDs.randomBase64UUID(random()));
        final ShardId shardId = new ShardId(new Index("test", IndexMetadata.INDEX_UUID_NA_VALUE), 0);

        final Metadata metadata = Metadata.builder()
            .put(
                IndexMetadata.builder("test")
                    .settings(settings(Version.CURRENT))
                    .numberOfShards(1)
                    .numberOfReplicas(0)
                    .putInSyncAllocationIds(0, Set.of(AllocationId.newInitializing().getId()))
            )
            .build();

        final RoutingTable routingTable = RoutingTable.builder()
            .addAsNewRestore(
                metadata.index("test"),
                new RecoverySource.SnapshotRecoverySource("_restore_uuid", snapshot, Version.CURRENT, indexId),
                new HashSet<>()
            )
            .build();

        final ImmutableOpenMap.Builder<ShardId, RestoreInProgress.ShardRestoreStatus> shards = ImmutableOpenMap.builder();
        shards.put(shardId, new RestoreInProgress.ShardRestoreStatus("node1"));

        final RestoreInProgress.Builder restores = new RestoreInProgress.Builder().add(
            new RestoreInProgress.Entry("_restore_uuid", snapshot, RestoreInProgress.State.INIT, List.of("test"), shards.build())
        );

        ClusterState clusterState = ClusterState.builder(new ClusterName(getTestName()))
            .metadata(metadata)
            .routingTable(routingTable)
            .putCustom(RestoreInProgress.TYPE, restores.build())
            .nodes(
                DiscoveryNodes.builder().add(newNode("node1")).add(newNode("node2")) // node2 is added because DiskThresholdDecider
                                                                                     // automatically ignore single-node clusters
            )
            .build();

        assertThat(
            shardsWithState(clusterState.getRoutingNodes(), UNASSIGNED).stream()
                .map(ShardRouting::unassignedInfo)
                .allMatch(unassignedInfo -> Reason.NEW_INDEX_RESTORED.equals(unassignedInfo.getReason())),
            is(true)
        );
        assertThat(
            shardsWithState(clusterState.getRoutingNodes(), UNASSIGNED).stream()
                .map(ShardRouting::unassignedInfo)
                .allMatch(unassignedInfo -> AllocationStatus.NO_ATTEMPT.equals(unassignedInfo.getLastAllocationStatus())),
            is(true)
        );
        assertThat(shardsWithState(clusterState.getRoutingNodes(), UNASSIGNED).size(), equalTo(1));

        final AtomicReference<SnapshotShardSizeInfo> snapshotShardSizeInfoRef = new AtomicReference<>(SnapshotShardSizeInfo.EMPTY);
        final AllocationService strategy = new AllocationService(
            deciders,
            new TestGatewayAllocator(),
            new BalancedShardsAllocator(Settings.EMPTY),
            clusterInfoService,
            snapshotShardSizeInfoRef::get
        );

        // reroute triggers snapshot shard size fetching
        clusterState = strategy.reroute(clusterState, "reroute");
        logShardStates(clusterState);

        // shard cannot be assigned yet as the snapshot shard size is unknown
        assertThat(
            shardsWithState(clusterState.getRoutingNodes(), UNASSIGNED).stream()
                .map(ShardRouting::unassignedInfo)
                .allMatch(unassignedInfo -> AllocationStatus.FETCHING_SHARD_DATA.equals(unassignedInfo.getLastAllocationStatus())),
            is(true)
        );
        assertThat(shardsWithState(clusterState.getRoutingNodes(), UNASSIGNED).size(), equalTo(1));

        final SnapshotShard snapshotShard = new SnapshotShard(snapshot, indexId, shardId);
        final ImmutableOpenMap.Builder<SnapshotShard, Long> snapshotShardSizes = ImmutableOpenMap.builder();

        final boolean shouldAllocate;
        if (randomBoolean()) {
            logger.info("--> simulating snapshot shards size retrieval success");
            snapshotShardSizes.put(snapshotShard, shardSizeInBytes);
            logger.info("--> shard allocation depends on its size");
            shouldAllocate = shardSizeInBytes < usages.get("node1").getFreeBytes();
        } else {
            logger.info("--> simulating snapshot shards size retrieval failure");
            snapshotShardSizes.put(snapshotShard, ShardRouting.UNAVAILABLE_EXPECTED_SHARD_SIZE);
            logger.info("--> shard is always allocated when its size could not be retrieved");
            shouldAllocate = true;
        }
        snapshotShardSizeInfoRef.set(new SnapshotShardSizeInfo(snapshotShardSizes.build()));

        // reroute uses the previous snapshot shard size
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);
        logShardStates(clusterState);

        assertThat(shardsWithState(clusterState.getRoutingNodes(), UNASSIGNED).size(), equalTo(shouldAllocate ? 0 : 1));
        assertThat(shardsWithState(clusterState.getRoutingNodes(), "test", INITIALIZING, STARTED).size(), equalTo(shouldAllocate ? 1 : 0));
    }

    public void logShardStates(ClusterState state) {
        RoutingNodes rn = state.getRoutingNodes();
        logger.info(
            "--> counts: total: {}, unassigned: {}, initializing: {}, relocating: {}, started: {}",
            rn.shards(shard -> true).size(),
            shardsWithState(rn, UNASSIGNED).size(),
            shardsWithState(rn, INITIALIZING).size(),
            shardsWithState(rn, RELOCATING).size(),
            shardsWithState(rn, STARTED).size()
        );
        logger.info(
            "--> unassigned: {}, initializing: {}, relocating: {}, started: {}",
            shardsWithState(rn, UNASSIGNED),
            shardsWithState(rn, INITIALIZING),
            shardsWithState(rn, RELOCATING),
            shardsWithState(rn, STARTED)
        );
    }

    /**
     * ClusterInfo that always reports /dev/null for the shards' data paths.
     */
    static class DevNullClusterInfo extends ClusterInfo {
        DevNullClusterInfo(
            ImmutableOpenMap<String, DiskUsage> leastAvailableSpaceUsage,
            ImmutableOpenMap<String, DiskUsage> mostAvailableSpaceUsage,
            ImmutableOpenMap<String, Long> shardSizes
        ) {
            this(leastAvailableSpaceUsage, mostAvailableSpaceUsage, shardSizes, ImmutableOpenMap.of());
        }

        DevNullClusterInfo(
            ImmutableOpenMap<String, DiskUsage> leastAvailableSpaceUsage,
            ImmutableOpenMap<String, DiskUsage> mostAvailableSpaceUsage,
            ImmutableOpenMap<String, Long> shardSizes,
            ImmutableOpenMap<NodeAndPath, ReservedSpace> reservedSpace
        ) {
            super(leastAvailableSpaceUsage, mostAvailableSpaceUsage, shardSizes, null, null, reservedSpace);
        }

        @Override
        public String getDataPath(ShardRouting shardRouting) {
            return "/dev/null";
        }
    }
}
