/*
 * Copyright 2014-2020 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.cluster;

import io.aeron.*;
import io.aeron.archive.client.*;
import io.aeron.archive.codecs.ControlResponseCode;
import io.aeron.archive.codecs.SourceLocation;
import io.aeron.archive.status.RecordingPos;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.ClusterClock;
import io.aeron.cluster.client.ClusterException;
import io.aeron.cluster.codecs.*;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusterMarkFile;
import io.aeron.cluster.service.RecoveryState;
import io.aeron.driver.MinMulticastFlowControl;
import io.aeron.exceptions.AeronException;
import io.aeron.logbuffer.ControlledFragmentHandler;
import io.aeron.security.Authenticator;
import io.aeron.status.ReadableCounter;
import org.agrona.*;
import org.agrona.collections.*;
import org.agrona.concurrent.*;
import org.agrona.concurrent.status.CountersReader;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static io.aeron.Aeron.NULL_VALUE;
import static io.aeron.CommonContext.*;
import static io.aeron.archive.client.AeronArchive.NULL_LENGTH;
import static io.aeron.archive.client.AeronArchive.NULL_POSITION;
import static io.aeron.archive.codecs.SourceLocation.LOCAL;
import static io.aeron.cluster.ClusterMember.quorumPosition;
import static io.aeron.cluster.ClusterSession.State.*;
import static io.aeron.cluster.ConsensusModule.Configuration.*;
import static io.aeron.cluster.client.AeronCluster.SESSION_HEADER_LENGTH;
import static io.aeron.cluster.service.ClusteredServiceContainer.Configuration.MARK_FILE_UPDATE_INTERVAL_NS;
import static java.lang.Math.min;
import static org.agrona.BitUtil.findNextPositivePowerOfTwo;

class ConsensusModuleAgent implements Agent
{
    static final long SLOW_TICK_INTERVAL_NS = TimeUnit.MILLISECONDS.toNanos(10);
    private static final int SERVICE_MESSAGE_LIMIT = 20;

    private final long sessionTimeoutNs;
    private final long leaderHeartbeatIntervalNs;
    private final long leaderHeartbeatTimeoutNs;
    private long nextSessionId = 1;
    private long nextServiceSessionId = Long.MIN_VALUE + 1;
    private long logServiceSessionId = Long.MIN_VALUE;
    private long leadershipTermId = NULL_VALUE;
    private long replayLeadershipTermId = NULL_VALUE;
    private long expectedAckPosition = 0;
    private long serviceAckId = 0;
    private long terminationPosition = NULL_POSITION;
    private long notifiedCommitPosition = 0;
    private long lastAppendPosition = 0;
    private long timeOfLastLogUpdateNs = 0;
    private long timeOfLastAppendPositionNs = 0;
    private long timeOfLastMarkFileUpdateNs;
    private long timeOfLastSlowTickNs;
    private int pendingServiceMessageHeadOffset = 0;
    private int uncommittedServiceMessages = 0;
    private int memberId;
    private int highMemberId;
    private int pendingMemberRemovals = 0;
    private int logPublicationTag;
    private int logPublicationChannelTag;
    private final int logSubscriptionTag;
    private final int logSubscriptionChannelTag;
    private ReadableCounter appendPosition;
    private final Counter commitPosition;
    private ConsensusModule.State state = ConsensusModule.State.INIT;
    private Cluster.Role role = Cluster.Role.FOLLOWER;
    private ClusterMember[] clusterMembers;
    private ClusterMember[] passiveMembers = ClusterMember.EMPTY_CLUSTER_MEMBER_ARRAY;
    private ClusterMember leaderMember;
    private ClusterMember thisMember;
    private long[] rankedPositions;
    private final long[] serviceClientIds;
    private final ArrayDeque<ServiceAck>[] serviceAckQueues;
    private final Counter clusterRoleCounter;
    private final ClusterMarkFile markFile;
    private final AgentInvoker aeronClientInvoker;
    private final ClusterClock clusterClock;
    private final TimeUnit clusterTimeUnit;
    private final Counter moduleState;
    private final Counter controlToggle;
    private final TimerService timerService;
    private final ConsensusModuleAdapter consensusModuleAdapter;
    private final ServiceProxy serviceProxy;
    private final IngressAdapter ingressAdapter;
    private final EgressPublisher egressPublisher;
    private final LogPublisher logPublisher;
    private final LogAdapter logAdapter;
    private final MemberStatusAdapter memberStatusAdapter;
    private final MemberStatusPublisher memberStatusPublisher = new MemberStatusPublisher();
    private final Long2ObjectHashMap<ClusterSession> sessionByIdMap = new Long2ObjectHashMap<>();
    private final ArrayList<ClusterSession> pendingSessions = new ArrayList<>();
    private final ArrayList<ClusterSession> rejectedSessions = new ArrayList<>();
    private final ArrayList<ClusterSession> redirectSessions = new ArrayList<>();
    private final Int2ObjectHashMap<ClusterMember> clusterMemberByIdMap = new Int2ObjectHashMap<>();
    private final Long2LongCounterMap expiredTimerCountByCorrelationIdMap = new Long2LongCounterMap(0);
    private final ArrayDeque<ClusterSession> uncommittedClosedSessions = new ArrayDeque<>();
    private final LongArrayQueue uncommittedTimers = new LongArrayQueue(Long.MAX_VALUE);
    private final ExpandableRingBuffer pendingServiceMessages = new ExpandableRingBuffer();
    private final ExpandableRingBuffer.MessageConsumer serviceSessionMessageAppender =
        this::serviceSessionMessageAppender;
    private final ExpandableRingBuffer.MessageConsumer leaderServiceSessionMessageSweeper =
        this::leaderServiceSessionMessageSweeper;
    private final ExpandableRingBuffer.MessageConsumer followerServiceSessionMessageSweeper =
        this::followerServiceSessionMessageSweeper;
    private final Authenticator authenticator;
    private final ClusterSessionProxy sessionProxy;
    private final Aeron aeron;
    private AeronArchive archive;
    private final ConsensusModule.Context ctx;
    private final MutableDirectBuffer tempBuffer;
    private final IdleStrategy idleStrategy;
    private final RecordingLog recordingLog;
    private final ArrayList<RecordingLog.Snapshot> dynamicJoinSnapshots = new ArrayList<>();
    private RecordingLog.RecoveryPlan recoveryPlan;
    private Election election;
    private DynamicJoin dynamicJoin;
    private ClusterTermination clusterTermination;
    private long logSubscriptionId = NULL_VALUE;
    private String liveLogDestination;
    private String replayLogDestination;
    private String clientFacingEndpoints;
    private final UnavailableCounterHandler unavailableCounterHandler = this::onUnavailableCounter;

    ConsensusModuleAgent(final ConsensusModule.Context ctx)
    {
        this.ctx = ctx;
        this.aeron = ctx.aeron();
        this.clusterClock = ctx.clusterClock();
        this.clusterTimeUnit = clusterClock.timeUnit();
        this.sessionTimeoutNs = ctx.sessionTimeoutNs();
        this.leaderHeartbeatIntervalNs = ctx.leaderHeartbeatIntervalNs();
        this.leaderHeartbeatTimeoutNs = ctx.leaderHeartbeatTimeoutNs();
        this.egressPublisher = ctx.egressPublisher();
        this.moduleState = ctx.moduleStateCounter();
        this.commitPosition = ctx.commitPositionCounter();
        this.controlToggle = ctx.controlToggleCounter();
        this.logPublisher = ctx.logPublisher();
        this.idleStrategy = ctx.idleStrategy();
        this.timerService = new TimerService(
            this,
            clusterTimeUnit,
            0,
            findNextPositivePowerOfTwo(clusterTimeUnit.convert(ctx.wheelTickResolutionNs(), TimeUnit.NANOSECONDS)),
            ctx.ticksPerWheel());
        this.clusterMembers = ClusterMember.parse(ctx.clusterMembers());
        this.sessionProxy = new ClusterSessionProxy(egressPublisher);
        this.memberId = ctx.clusterMemberId();
        this.clusterRoleCounter = ctx.clusterNodeRoleCounter();
        this.markFile = ctx.clusterMarkFile();
        this.recordingLog = ctx.recordingLog();
        this.tempBuffer = ctx.tempBuffer();
        this.serviceClientIds = new long[ctx.serviceCount()];
        Arrays.fill(serviceClientIds, NULL_VALUE);
        this.serviceAckQueues = ServiceAck.newArray(ctx.serviceCount());
        this.highMemberId = ClusterMember.highMemberId(clusterMembers);
        this.logPublicationChannelTag = (int)aeron.nextCorrelationId();
        this.logSubscriptionChannelTag = (int)aeron.nextCorrelationId();
        this.logPublicationTag = (int)aeron.nextCorrelationId();
        this.logSubscriptionTag = (int)aeron.nextCorrelationId();

        aeronClientInvoker = aeron.conductorAgentInvoker();
        aeronClientInvoker.invoke();

        rankedPositions = new long[ClusterMember.quorumThreshold(clusterMembers.length)];
        role(Cluster.Role.FOLLOWER);

        ClusterMember.addClusterMemberIds(clusterMembers, clusterMemberByIdMap);
        thisMember = ClusterMember.determineMember(clusterMembers, ctx.clusterMemberId(), ctx.memberEndpoints());
        leaderMember = thisMember;

        final ChannelUri memberStatusUri = ChannelUri.parse(ctx.memberStatusChannel());
        memberStatusUri.put(ENDPOINT_PARAM_NAME, thisMember.memberFacingEndpoint());

        final int statusStreamId = ctx.memberStatusStreamId();
        memberStatusAdapter = new MemberStatusAdapter(
            aeron.addSubscription(memberStatusUri.toString(), statusStreamId), this);

        ClusterMember.addMemberStatusPublications(clusterMembers, thisMember, memberStatusUri, statusStreamId, aeron);

        ingressAdapter = new IngressAdapter(ctx.ingressFragmentLimit(), this, ctx.invalidRequestCounter());
        logAdapter = new LogAdapter(this);

        consensusModuleAdapter = new ConsensusModuleAdapter(
            aeron.addSubscription(ctx.serviceControlChannel(), ctx.consensusModuleStreamId()), this);
        serviceProxy = new ServiceProxy(aeron.addPublication(ctx.serviceControlChannel(), ctx.serviceStreamId()));

        authenticator = ctx.authenticatorSupplier().get();
    }

    public void onClose()
    {
        if (!ctx.ownsAeronClient() && !aeron.isClosed())
        {
            aeron.removeUnavailableCounterHandler(unavailableCounterHandler);
            final CountedErrorHandler errorHandler = ctx.countedErrorHandler();
            for (final ClusterSession session : sessionByIdMap.values())
            {
                session.close(errorHandler);
            }

            CloseHelper.close(errorHandler, ingressAdapter);
            closeExistingLog();
            ClusterMember.closeMemberPublications(errorHandler, clusterMembers);
            CloseHelper.close(errorHandler, memberStatusAdapter);
            CloseHelper.close(errorHandler, serviceProxy);
            CloseHelper.close(errorHandler, consensusModuleAdapter);
            CloseHelper.close(errorHandler, archive);
        }

        ctx.close();
    }

    public void onStart()
    {
        archive = AeronArchive.connect(ctx.archiveContext().clone());

        if (null == (dynamicJoin = requiresDynamicJoin()))
        {
            recoveryPlan = recordingLog.createRecoveryPlan(archive, ctx.serviceCount());
            try (Counter ignore = addRecoveryStateCounter(recoveryPlan))
            {
                if (!recoveryPlan.snapshots.isEmpty())
                {
                    recoverFromSnapshot(recoveryPlan.snapshots.get(0), archive);
                }

                while (!ServiceAck.hasReachedPosition(expectedAckPosition, serviceAckId, serviceAckQueues))
                {
                    idle(consensusModuleAdapter.poll());
                }

                captureServiceClientIds();
                ++serviceAckId;
            }

            if (ConsensusModule.State.SUSPENDED != state)
            {
                state(ConsensusModule.State.ACTIVE);
            }

            election = new Election(
                true,
                recoveryPlan.lastLeadershipTermId,
                recoveryPlan.appendedLogPosition,
                clusterMembers,
                clusterMemberByIdMap,
                thisMember,
                memberStatusAdapter,
                memberStatusPublisher,
                ctx,
                this);
        }

        aeron.addUnavailableCounterHandler(unavailableCounterHandler);
    }

    public int doWork()
    {
        int workCount = 0;

        final long now = clusterClock.time();
        final long nowNs = clusterTimeUnit.toNanos(now);

        if (nowNs >= (timeOfLastSlowTickNs + SLOW_TICK_INTERVAL_NS))
        {
            timeOfLastSlowTickNs = nowNs;
            workCount += slowTickWork(clusterTimeUnit.toMillis(now), nowNs);
        }

        if (null != dynamicJoin)
        {
            workCount += dynamicJoin.doWork(nowNs);
        }
        else if (null != election)
        {
            workCount += election.doWork(nowNs);
        }
        else
        {
            workCount += consensusWork(now, nowNs);
        }

        return workCount;
    }

    public String roleName()
    {
        return "consensus-module";
    }

    public void onSessionConnect(
        final long correlationId,
        final int responseStreamId,
        final int version,
        final String responseChannel,
        final byte[] encodedCredentials)
    {
        final long clusterSessionId = Cluster.Role.LEADER == role ? nextSessionId++ : NULL_VALUE;
        final ClusterSession session = new ClusterSession(clusterSessionId, responseStreamId, responseChannel);
        final long now = clusterClock.time();
        session.lastActivityNs(clusterTimeUnit.toNanos(now), correlationId);
        session.connect(aeron);

        if (Cluster.Role.LEADER != role)
        {
            redirectSessions.add(session);
        }
        else
        {
            if (AeronCluster.Configuration.PROTOCOL_MAJOR_VERSION != SemanticVersion.major(version))
            {
                final String detail = SESSION_INVALID_VERSION_MSG + " " + SemanticVersion.toString(version) +
                    ", cluster is " + SemanticVersion.toString(AeronCluster.Configuration.PROTOCOL_SEMANTIC_VERSION);
                session.reject(EventCode.ERROR, detail);
                rejectedSessions.add(session);
            }
            else if (pendingSessions.size() + sessionByIdMap.size() >= ctx.maxConcurrentSessions())
            {
                session.reject(EventCode.ERROR, SESSION_LIMIT_MSG);
                rejectedSessions.add(session);
            }
            else
            {
                authenticator.onConnectRequest(session.id(), encodedCredentials, clusterTimeUnit.toMillis(now));
                pendingSessions.add(session);
            }
        }
    }

    public void onSessionClose(final long leadershipTermId, final long clusterSessionId)
    {
        final ClusterSession session = sessionByIdMap.get(clusterSessionId);
        if (leadershipTermId == this.leadershipTermId && null != session && Cluster.Role.LEADER == role)
        {
            session.close(CloseReason.CLIENT_ACTION, ctx.countedErrorHandler());

            if (logPublisher.appendSessionClose(session, leadershipTermId, clusterClock.time()))
            {
                session.closedLogPosition(logPublisher.position());
                uncommittedClosedSessions.addLast(session);
                sessionByIdMap.remove(clusterSessionId);
            }
        }
    }

    public ControlledFragmentAssembler.Action onIngressMessage(
        final long leadershipTermId,
        final long clusterSessionId,
        final DirectBuffer buffer,
        final int offset,
        final int length)
    {
        if (leadershipTermId != this.leadershipTermId || Cluster.Role.LEADER != role)
        {
            return ControlledFragmentHandler.Action.CONTINUE;
        }

        final ClusterSession session = sessionByIdMap.get(clusterSessionId);
        if (null == session || session.state() == CLOSED)
        {
            return ControlledFragmentHandler.Action.CONTINUE;
        }

        if (session.state() == OPEN)
        {
            final long now = clusterClock.time();

            if (logPublisher.appendMessage(leadershipTermId, clusterSessionId, now, buffer, offset, length) > 0)
            {
                session.timeOfLastActivityNs(clusterTimeUnit.toNanos(now));
                return ControlledFragmentHandler.Action.CONTINUE;
            }
        }

        return ControlledFragmentHandler.Action.ABORT;
    }

    public void onSessionKeepAlive(final long leadershipTermId, final long clusterSessionId)
    {
        if (Cluster.Role.LEADER == role && leadershipTermId == this.leadershipTermId)
        {
            final ClusterSession session = sessionByIdMap.get(clusterSessionId);
            if (null != session && session.state() == OPEN)
            {
                session.timeOfLastActivityNs(clusterTimeUnit.toNanos(clusterClock.time()));
            }
        }
    }

    public void onChallengeResponse(
        final long correlationId, final long clusterSessionId, final byte[] encodedCredentials)
    {
        if (Cluster.Role.LEADER == role)
        {
            for (int lastIndex = pendingSessions.size() - 1, i = lastIndex; i >= 0; i--)
            {
                final ClusterSession session = pendingSessions.get(i);

                if (session.id() == clusterSessionId && session.state() == CHALLENGED)
                {
                    final long now = clusterClock.time();
                    final long nowMs = clusterTimeUnit.toMillis(now);
                    session.lastActivityNs(clusterTimeUnit.toNanos(now), correlationId);
                    authenticator.onChallengeResponse(clusterSessionId, encodedCredentials, nowMs);
                    break;
                }
            }
        }
    }

    public boolean onTimerEvent(final long correlationId)
    {
        final long appendPosition = logPublisher.appendTimer(correlationId, leadershipTermId, clusterClock.time());
        if (appendPosition > 0)
        {
            uncommittedTimers.offerLong(appendPosition);
            uncommittedTimers.offerLong(correlationId);
            return true;
        }

        return false;
    }

    public void onCanvassPosition(final long logLeadershipTermId, final long logPosition, final int followerMemberId)
    {
        if (null != election)
        {
            election.onCanvassPosition(logLeadershipTermId, logPosition, followerMemberId);
        }
        else if (Cluster.Role.LEADER == role)
        {
            final ClusterMember follower = clusterMemberByIdMap.get(followerMemberId);
            if (null != follower && logLeadershipTermId <= leadershipTermId)
            {
                final RecordingLog.Entry termEntry = recordingLog.findTermEntry(
                    logLeadershipTermId < leadershipTermId ? logLeadershipTermId + 1 : logLeadershipTermId);
                if (null != termEntry)
                {
                    final long appendPosition = logPublisher.position();
                    memberStatusPublisher.newLeadershipTerm(
                        follower.publication(),
                        logLeadershipTermId,
                        logLeadershipTermId < leadershipTermId ? termEntry.termBaseLogPosition : appendPosition,
                        leadershipTermId,
                        appendPosition,
                        termEntry.timestamp,
                        thisMember.id(),
                        logPublisher.sessionId(),
                        false);
                }
            }
        }
    }

    public void onRequestVote(
        final long logLeadershipTermId, final long logPosition, final long candidateTermId, final int candidateId)
    {
        if (null != election)
        {
            election.onRequestVote(logLeadershipTermId, logPosition, candidateTermId, candidateId);
        }
        else if (candidateTermId > leadershipTermId)
        {
            ctx.countedErrorHandler().onError(new ClusterException(
                "unexpected vote request", AeronException.Category.WARN));
            enterElection(clusterClock.timeNanos());
            election.onRequestVote(logLeadershipTermId, logPosition, candidateTermId, candidateId);
        }
    }

    public void onVote(
        final long candidateTermId,
        final long logLeadershipTermId,
        final long logPosition,
        final int candidateMemberId,
        final int followerMemberId,
        final boolean vote)
    {
        if (null != election)
        {
            election.onVote(
                candidateTermId, logLeadershipTermId, logPosition, candidateMemberId, followerMemberId, vote);
        }
    }

    public void onNewLeadershipTerm(
        final long logLeadershipTermId,
        final long logTruncatePosition,
        final long leadershipTermId,
        final long logPosition,
        final long timestamp,
        final int leaderId,
        final int logSessionId,
        final boolean isStartup)
    {
        if (null != election)
        {
            election.onNewLeadershipTerm(
                logLeadershipTermId,
                logTruncatePosition,
                leadershipTermId,
                logPosition,
                timestamp,
                leaderId,
                logSessionId,
                isStartup);
        }
        else if (Cluster.Role.FOLLOWER == role &&
            leadershipTermId == this.leadershipTermId &&
            leaderId == leaderMember.id())
        {
            timeOfLastLogUpdateNs = clusterClock.timeNanos();
            notifiedCommitPosition = Math.max(notifiedCommitPosition, logPosition);
        }
        else if (leadershipTermId > this.leadershipTermId)
        {
            ctx.countedErrorHandler().onError(new ClusterException(
                "unexpected new leadership term", AeronException.Category.WARN));
            enterElection(clusterClock.timeNanos());
        }
    }

    public void onAppendPosition(final long leadershipTermId, final long logPosition, final int followerMemberId)
    {
        if (null != election)
        {
            election.onAppendPosition(leadershipTermId, logPosition, followerMemberId);
        }
        else if (Cluster.Role.LEADER == role && leadershipTermId == this.leadershipTermId)
        {
            final ClusterMember follower = clusterMemberByIdMap.get(followerMemberId);
            if (null != follower)
            {
                follower
                    .logPosition(logPosition)
                    .timeOfLastAppendPositionNs(clusterClock.timeNanos());
                trackCatchupCompletion(follower, leadershipTermId);
            }
        }
    }

    public void onCommitPosition(final long leadershipTermId, final long logPosition, final int leaderMemberId)
    {
        if (null != election)
        {
            election.onCommitPosition(leadershipTermId, logPosition, leaderMemberId);
        }
        else if (Cluster.Role.FOLLOWER == role &&
            leadershipTermId == this.leadershipTermId &&
            leaderMemberId == leaderMember.id())
        {
            timeOfLastLogUpdateNs = clusterClock.timeNanos();
            notifiedCommitPosition = logPosition;
        }
        else if (leadershipTermId > this.leadershipTermId)
        {
            ctx.countedErrorHandler().onError(new ClusterException("unexpected commit position from new leader"));
            enterElection(clusterClock.timeNanos());
        }
    }

    public void onCatchupPosition(final long leadershipTermId, final long logPosition, final int followerMemberId)
    {
        if (Cluster.Role.LEADER == role && leadershipTermId == this.leadershipTermId)
        {
            final ClusterMember follower = clusterMemberByIdMap.get(followerMemberId);
            if (null != follower && follower.catchupReplaySessionId() == NULL_VALUE)
            {
                final String replayChannel = new ChannelUriStringBuilder()
                    .media(CommonContext.UDP_MEDIA)
                    .endpoint(follower.transferEndpoint())
                    .isSessionIdTagged(true)
                    .sessionId(logPublicationTag)
                    .linger(0L)
                    .eos(false)
                    .build();

                follower.catchupReplaySessionId(archive.startReplay(
                    logRecordingId(), logPosition, Long.MAX_VALUE, replayChannel, ctx.logStreamId()));

                follower.catchupReplayCorrelationId(archive.lastCorrelationId());
            }
        }
    }

    public void onStopCatchup(final long leadershipTermId, final int followerMemberId)
    {
        if (null != replayLogDestination && followerMemberId == memberId && leadershipTermId == this.leadershipTermId)
        {
            logAdapter.asyncRemoveDestination(replayLogDestination);
            replayLogDestination = null;
        }
    }

    public void onAddPassiveMember(final long correlationId, final String memberEndpoints)
    {
        if (null == election && Cluster.Role.LEADER == role)
        {
            if (ClusterMember.isNotDuplicateEndpoint(passiveMembers, memberEndpoints))
            {
                final ClusterMember newMember = ClusterMember.parseEndpoints(++highMemberId, memberEndpoints);

                newMember.correlationId(correlationId);
                passiveMembers = ClusterMember.addMember(passiveMembers, newMember);
                clusterMemberByIdMap.put(newMember.id(), newMember);

                ClusterMember.addMemberStatusPublication(
                    newMember, ChannelUri.parse(ctx.memberStatusChannel()), ctx.memberStatusStreamId(), aeron);

                logPublisher.addPassiveFollower(newMember.logEndpoint());
            }
        }
        else if (null == election && Cluster.Role.FOLLOWER == role)
        {
            memberStatusPublisher.addPassiveMember(leaderMember.publication(), correlationId, memberEndpoints);
        }
    }

    public void onClusterMembersChange(
        final long correlationId, final int leaderMemberId, final String activeMembers, final String passiveMembers)
    {
        if (null != dynamicJoin)
        {
            dynamicJoin.onClusterMembersChange(correlationId, leaderMemberId, activeMembers, passiveMembers);
        }
    }

    public void onSnapshotRecordingQuery(final long correlationId, final int requestMemberId)
    {
        if (null == election && Cluster.Role.LEADER == role)
        {
            final ClusterMember requester = clusterMemberByIdMap.get(requestMemberId);
            if (null != requester)
            {
                memberStatusPublisher.snapshotRecording(
                    requester.publication(),
                    correlationId,
                    recoveryPlan,
                    ClusterMember.encodeAsString(clusterMembers));
            }
        }
    }

    public void onSnapshotRecordings(final long correlationId, final SnapshotRecordingsDecoder decoder)
    {
        if (null != dynamicJoin)
        {
            dynamicJoin.onSnapshotRecordings(correlationId, decoder);
        }
    }

    public void onJoinCluster(final long leadershipTermId, final int memberId)
    {
        if (null == election && Cluster.Role.LEADER == role)
        {
            final ClusterMember member = clusterMemberByIdMap.get(memberId);
            final long snapshotLeadershipTermId = recoveryPlan.snapshots.isEmpty() ?
                NULL_VALUE : recoveryPlan.snapshots.get(0).leadershipTermId;

            if (null != member && !member.hasRequestedJoin() && leadershipTermId <= snapshotLeadershipTermId)
            {
                if (null == member.publication())
                {
                    final ChannelUri memberStatusUri = ChannelUri.parse(ctx.memberStatusChannel());
                    final int streamId = ctx.memberStatusStreamId();
                    ClusterMember.addMemberStatusPublication(member, memberStatusUri, streamId, aeron);
                    logPublisher.addPassiveFollower(member.logEndpoint());
                }

                member.hasRequestedJoin(true);
            }
        }
    }

    public void onTerminationPosition(final long logPosition)
    {
        if (Cluster.Role.FOLLOWER == role)
        {
            terminationPosition = logPosition;
        }
    }

    public void onTerminationAck(final long logPosition, final int memberId)
    {
        if (Cluster.Role.LEADER == role && logPosition == terminationPosition)
        {
            final ClusterMember member = clusterMemberByIdMap.get(memberId);
            if (null != member)
            {
                member.hasTerminated(true);

                if (clusterTermination.canTerminate(clusterMembers, terminationPosition, clusterClock.timeNanos()))
                {
                    recordingLog.commitLogPosition(leadershipTermId, logPosition);
                    state(ConsensusModule.State.CLOSED);
                    ctx.terminationHook().run();
                }
            }
        }
    }

    public void onBackupQuery(
        final long correlationId,
        final int responseStreamId,
        final int version,
        final String responseChannel,
        final byte[] encodedCredentials)
    {
        if (Cluster.Role.LEADER != role && null == election)
        {
            memberStatusPublisher.backupQuery(
                leaderMember.publication(),
                correlationId,
                responseStreamId,
                version,
                responseChannel,
                encodedCredentials);
        }
        else if (state == ConsensusModule.State.ACTIVE || state == ConsensusModule.State.SUSPENDED)
        {
            final ClusterSession session = new ClusterSession(NULL_VALUE, responseStreamId, responseChannel);
            final long now = clusterClock.time();
            session.lastActivityNs(clusterTimeUnit.toNanos(now), correlationId);
            session.markAsBackupSession();
            session.connect(aeron);

            if (AeronCluster.Configuration.PROTOCOL_MAJOR_VERSION != SemanticVersion.major(version))
            {
                final String detail = SESSION_INVALID_VERSION_MSG + " " + SemanticVersion.toString(version) +
                    ", cluster is " + SemanticVersion.toString(AeronCluster.Configuration.PROTOCOL_SEMANTIC_VERSION);
                session.reject(EventCode.ERROR, detail);
                rejectedSessions.add(session);
            }
            else if (pendingSessions.size() + sessionByIdMap.size() >= ctx.maxConcurrentSessions())
            {
                session.reject(EventCode.ERROR, SESSION_LIMIT_MSG);
                rejectedSessions.add(session);
            }
            else
            {
                authenticator.onConnectRequest(session.id(), encodedCredentials, clusterTimeUnit.toMillis(now));
                pendingSessions.add(session);
            }
        }
    }

    public void onRemoveMember(final int memberId, final boolean isPassive)
    {
        final ClusterMember member = clusterMemberByIdMap.get(memberId);
        if (null == election && Cluster.Role.LEADER == role && null != member)
        {
            if (isPassive)
            {
                passiveMembers = ClusterMember.removeMember(passiveMembers, memberId);

                member.closePublication(ctx.countedErrorHandler());
                logPublisher.removePassiveFollower(member.logEndpoint());

                clusterMemberByIdMap.remove(memberId);
                clusterMemberByIdMap.compact();
            }
            else
            {
                final ClusterMember[] newClusterMembers = ClusterMember.removeMember(clusterMembers, memberId);
                final String newClusterMembersString = ClusterMember.encodeAsString(newClusterMembers);

                final long now = clusterClock.time();
                final long position = logPublisher.appendMembershipChangeEvent(
                    leadershipTermId,
                    now,
                    thisMember.id(),
                    clusterMembers.length,
                    ChangeType.QUIT,
                    memberId,
                    newClusterMembersString);

                if (position > 0)
                {
                    timeOfLastLogUpdateNs = clusterTimeUnit.toNanos(now) - leaderHeartbeatIntervalNs;
                    member.removalPosition(position);
                    pendingMemberRemovals++;
                }
            }
        }
    }

    public void onClusterMembersQuery(final long correlationId, final boolean isExtendedRequest)
    {
        if (isExtendedRequest)
        {
            serviceProxy.clusterMembersExtendedResponse(
                correlationId, clusterClock.timeNanos(), leaderMember.id(), memberId, clusterMembers, passiveMembers);
        }
        else
        {
            serviceProxy.clusterMembersResponse(
                correlationId,
                leaderMember.id(),
                ClusterMember.encodeAsString(clusterMembers),
                ClusterMember.encodeAsString(passiveMembers));
        }
    }

    void state(final ConsensusModule.State newState)
    {
        if (newState != state)
        {
            stateChange(state, newState, memberId);
            state = newState;
            moduleState.set(newState.code());
        }
    }

    @SuppressWarnings("unused")
    void stateChange(final ConsensusModule.State oldState, final ConsensusModule.State newState, final int memberId)
    {
        //System.out.println("CM State memberId=" + memberId + " " + oldState + " -> " + newState);
    }

    void role(final Cluster.Role newRole)
    {
        if (newRole != role)
        {
            roleChange(role, newRole, memberId);
            role = newRole;
            clusterRoleCounter.setOrdered(newRole.code());
        }
    }

    @SuppressWarnings("unused")
    void roleChange(final Cluster.Role oldRole, final Cluster.Role newRole, final int memberId)
    {
        //System.out.println("CM Role memberId=" + memberId + " " + oldRole + " -> " + newRole);
    }

    Cluster.Role role()
    {
        return role;
    }

    String logSubscriptionTags()
    {
        return logSubscriptionChannelTag + "," + logSubscriptionTag;
    }

    void prepareForNewLeadership(final long logPosition)
    {
        role(Cluster.Role.FOLLOWER);
        ClusterControl.ToggleState.deactivate(controlToggle);

        final long recordingId = logRecordingId();
        if (RecordingPos.NULL_RECORDING_ID != recordingId)
        {
            logPublisher.disconnect(ctx.countedErrorHandler());
            stopLogRecording();

            long stopPosition;
            idleStrategy.reset();
            while (AeronArchive.NULL_POSITION == (stopPosition = archive.getStopPosition(recordingId)))
            {
                idle();
            }

            archive.stopAllReplays(recordingId);

            if (stopPosition > logPosition)
            {
                archive.truncateRecording(recordingId, logPosition);
            }

            lastAppendPosition = logPosition;
            notifiedCommitPosition = logPosition;

            commitPosition.setOrdered(logPosition);
            restoreUncommittedEntries(logPosition);

            clearSessionsAfter(logPosition);
            for (final ClusterSession session : sessionByIdMap.values())
            {
                session.disconnect(ctx.countedErrorHandler());
            }
        }
    }

    void stopLogRecording()
    {
        if (NULL_VALUE != logSubscriptionId)
        {
            archive.tryStopRecording(logSubscriptionId);
            logSubscriptionId = NULL_VALUE;
        }

        if (null != replayLogDestination)
        {
            logAdapter.asyncRemoveDestination(replayLogDestination);
            replayLogDestination = null;
        }

        if (null != liveLogDestination)
        {
            logAdapter.asyncRemoveDestination(liveLogDestination);
            liveLogDestination = null;
        }
    }

    void appendPositionCounter(final ReadableCounter appendPositionCounter)
    {
        this.appendPosition = appendPositionCounter;
    }

    void clearSessionsAfter(final long logPosition)
    {
        for (final Iterator<ClusterSession> i = sessionByIdMap.values().iterator(); i.hasNext(); )
        {
            final ClusterSession session = i.next();
            if (session.openedLogPosition() > logPosition)
            {
                i.remove();
                session.close(ctx.countedErrorHandler());
            }
        }

        for (final ClusterSession session : pendingSessions)
        {
            session.close(ctx.countedErrorHandler());
        }

        pendingSessions.clear();
    }

    void onServiceCloseSession(final long clusterSessionId)
    {
        final ClusterSession session = sessionByIdMap.get(clusterSessionId);
        if (null != session)
        {
            session.close(CloseReason.SERVICE_ACTION, ctx.countedErrorHandler());

            if (Cluster.Role.LEADER == role &&
                logPublisher.appendSessionClose(session, leadershipTermId, clusterClock.time()))
            {
                final String msg = CloseReason.SERVICE_ACTION.name();
                egressPublisher.sendEvent(session, leadershipTermId, memberId, EventCode.CLOSED, msg);
                session.closedLogPosition(logPublisher.position());
                uncommittedClosedSessions.addLast(session);
                sessionByIdMap.remove(clusterSessionId);
            }
        }
    }

    void onServiceMessage(final long leadershipTermId, final DirectBuffer buffer, final int offset, final int length)
    {
        if (leadershipTermId == this.leadershipTermId)
        {
            enqueueServiceSessionMessage((MutableDirectBuffer)buffer, offset, length, nextServiceSessionId++);
        }
    }

    void onScheduleTimer(final long correlationId, final long deadline)
    {
        if (expiredTimerCountByCorrelationIdMap.get(correlationId) == 0)
        {
            timerService.scheduleTimer(correlationId, deadline);
        }
        else
        {
            expiredTimerCountByCorrelationIdMap.decrementAndGet(correlationId);
        }
    }

    void onCancelTimer(final long correlationId)
    {
        timerService.cancelTimer(correlationId);
    }

    void onServiceAck(
        final long logPosition, final long timestamp, final long ackId, final long relevantId, final int serviceId)
    {
        serviceAckQueues[serviceId].offerLast(new ServiceAck(ackId, logPosition, relevantId));

        if (ServiceAck.hasReachedPosition(logPosition, serviceAckId, serviceAckQueues))
        {
            if (ConsensusModule.State.SNAPSHOT == state)
            {
                final ServiceAck[] serviceAcks = consumeServiceAcks(logPosition, serviceId);
                ++serviceAckId;
                takeSnapshot(timestamp, logPosition, serviceAcks);

                final long nowNs = clusterClock.timeNanos();
                if (NULL_POSITION == terminationPosition)
                {
                    state(ConsensusModule.State.ACTIVE);
                    ClusterControl.ToggleState.reset(controlToggle);
                    for (final ClusterSession session : sessionByIdMap.values())
                    {
                        session.timeOfLastActivityNs(nowNs);
                    }
                }
                else
                {
                    serviceProxy.terminationPosition(terminationPosition);
                    if (null != clusterTermination)
                    {
                        clusterTermination.deadlineNs(nowNs + ctx.terminationTimeoutNs());
                    }

                    state(ConsensusModule.State.TERMINATING);
                }
            }
            else if (ConsensusModule.State.QUITTING == state)
            {
                state(ConsensusModule.State.CLOSED);
                ctx.terminationHook().run();
            }
            else if (ConsensusModule.State.TERMINATING == state)
            {
                final boolean canTerminate;
                if (null == clusterTermination)
                {
                    memberStatusPublisher.terminationAck(leaderMember.publication(), logPosition, memberId);
                    canTerminate = true;
                }
                else
                {
                    clusterTermination.onServicesTerminated();
                    canTerminate = clusterTermination.canTerminate(
                        clusterMembers, terminationPosition, clusterClock.timeNanos());
                }

                if (canTerminate)
                {
                    recordingLog.commitLogPosition(leadershipTermId, logPosition);
                    state(ConsensusModule.State.CLOSED);
                    ctx.terminationHook().run();
                }
            }
        }
    }

    private ServiceAck[] consumeServiceAcks(final long logPosition, final int serviceId)
    {
        final ServiceAck[] serviceAcks = new ServiceAck[serviceAckQueues.length];
        for (int id = 0, length = serviceAckQueues.length; id < length; id++)
        {
            final ServiceAck serviceAck = serviceAckQueues[id].pollFirst();
            if (null == serviceAck || serviceAck.logPosition() != logPosition)
            {
                throw new ClusterException("invalid ack for serviceId=" + serviceId +
                    " logPosition=" + logPosition + " " + serviceAck);
            }

            serviceAcks[id] = serviceAck;
        }

        return serviceAcks;
    }

    void onReplaySessionMessage(final long clusterSessionId, final long timestamp)
    {
        final ClusterSession clusterSession = sessionByIdMap.get(clusterSessionId);
        if (null == clusterSession)
        {
            logServiceSessionId = clusterSessionId;
            pendingServiceMessages.consume(followerServiceSessionMessageSweeper, Integer.MAX_VALUE);
        }
        else
        {
            clusterSession.timeOfLastActivityNs(clusterTimeUnit.toNanos(timestamp));
        }
    }

    void onReplayTimerEvent(final long correlationId)
    {
        if (!timerService.cancelTimer(correlationId))
        {
            expiredTimerCountByCorrelationIdMap.getAndIncrement(correlationId);
        }
    }

    void onReplaySessionOpen(
        final long logPosition,
        final long correlationId,
        final long clusterSessionId,
        final long timestamp,
        final int responseStreamId,
        final String responseChannel)
    {
        final ClusterSession session = new ClusterSession(clusterSessionId, responseStreamId, responseChannel);
        session.open(logPosition);
        session.lastActivityNs(clusterTimeUnit.toNanos(timestamp), correlationId);

        sessionByIdMap.put(clusterSessionId, session);
        if (clusterSessionId >= nextSessionId)
        {
            nextSessionId = clusterSessionId + 1;
        }
    }

    void onReplaySessionClose(final long clusterSessionId, final CloseReason closeReason)
    {
        final ClusterSession clusterSession = sessionByIdMap.remove(clusterSessionId);
        if (null != clusterSession)
        {
            clusterSession.close(closeReason, ctx.countedErrorHandler());
        }
    }

    void onReplayClusterAction(final long leadershipTermId, final ClusterAction action)
    {
        if (leadershipTermId == this.replayLeadershipTermId)
        {
            if (ClusterAction.SUSPEND == action)
            {
                state(ConsensusModule.State.SUSPENDED);
            }
            else if (ClusterAction.RESUME == action)
            {
                state(ConsensusModule.State.ACTIVE);
            }
            else if (ClusterAction.SNAPSHOT == action)
            {
                state(ConsensusModule.State.SNAPSHOT);
            }
        }
    }

    void onReplayNewLeadershipTermEvent(
        final long leadershipTermId,
        final long logPosition,
        final long timestamp,
        final long termBaseLogPosition,
        final TimeUnit timeUnit,
        final int appVersion)
    {
        if (timeUnit != clusterTimeUnit)
        {
            ctx.errorHandler().onError(new ClusterException(
                "incompatible timestamp units: " + clusterTimeUnit + " log=" + timeUnit,
                AeronException.Category.FATAL));
            state(ConsensusModule.State.CLOSED);
            ctx.terminationHook().run();
            return;
        }

        if (SemanticVersion.major(ctx.appVersion()) != SemanticVersion.major(appVersion))
        {
            ctx.errorHandler().onError(new ClusterException(
                "incompatible version: " + SemanticVersion.toString(ctx.appVersion()) +
                " log=" + SemanticVersion.toString(appVersion),
                AeronException.Category.FATAL));
            state(ConsensusModule.State.CLOSED);
            ctx.terminationHook().run();
            return;
        }

        leadershipTermId(leadershipTermId);

        if (null != election && null != appendPosition)
        {
            final long recordingId = RecordingPos.getRecordingId(aeron.countersReader(), appendPosition.counterId());
            election.onReplayNewLeadershipTermEvent(
                recordingId, leadershipTermId, logPosition, timestamp, termBaseLogPosition);
        }
    }

    void onReplayMembershipChange(
        final long leadershipTermId,
        final long logPosition,
        final int leaderMemberId,
        final ChangeType changeType,
        final int memberId,
        final String clusterMembers)
    {
        if (leadershipTermId == this.replayLeadershipTermId)
        {
            if (ChangeType.JOIN == changeType)
            {
                final ClusterMember[] newMembers = ClusterMember.parse(clusterMembers);
                if (memberId == this.memberId)
                {
                    this.clusterMembers = newMembers;
                    clusterMemberByIdMap.clear();
                    clusterMemberByIdMap.compact();
                    ClusterMember.addClusterMemberIds(newMembers, clusterMemberByIdMap);
                    thisMember = ClusterMember.findMember(this.clusterMembers, memberId);
                    leaderMember = ClusterMember.findMember(this.clusterMembers, leaderMemberId);

                    ClusterMember.addMemberStatusPublications(
                        newMembers,
                        thisMember,
                        ChannelUri.parse(ctx.memberStatusChannel()),
                        ctx.memberStatusStreamId(),
                        aeron);
                }
                else
                {
                    clusterMemberJoined(memberId, newMembers);
                }
            }
            else if (ChangeType.QUIT == changeType)
            {
                if (memberId == this.memberId)
                {
                    state(ConsensusModule.State.QUITTING);
                }
                else
                {
                    final boolean hasCurrentLeaderSteppedDown = leaderMemberId == memberId;
                    clusterMemberQuit(memberId);

                    if (hasCurrentLeaderSteppedDown)
                    {
                        commitPosition.proposeMaxOrdered(logPosition);
                        enterElection(clusterClock.timeNanos());
                    }
                }
            }
        }
    }

    void onLoadSession(
        final long clusterSessionId,
        final long correlationId,
        final long openedPosition,
        final long timeOfLastActivity,
        final CloseReason closeReason,
        final int responseStreamId,
        final String responseChannel)
    {
        sessionByIdMap.put(clusterSessionId, new ClusterSession(
            clusterSessionId,
            correlationId,
            openedPosition,
            timeOfLastActivity,
            responseStreamId,
            responseChannel,
            closeReason));

        if (clusterSessionId >= nextSessionId)
        {
            nextSessionId = clusterSessionId + 1;
        }
    }

    void onLoadPendingMessage(final DirectBuffer buffer, final int offset, final int length)
    {
        pendingServiceMessages.append(buffer, offset, length);
    }

    void onLoadConsensusModuleState(
        final long nextSessionId,
        final long nextServiceSessionId,
        final long logServiceSessionId,
        final int pendingMessageCapacity)
    {
        this.nextSessionId = nextSessionId;
        this.nextServiceSessionId = nextServiceSessionId;
        this.logServiceSessionId = logServiceSessionId;
        pendingServiceMessages.reset(pendingMessageCapacity);
    }

    void onLoadClusterMembers(final int memberId, final int highMemberId, final String members)
    {
        if (ctx.clusterMembersIgnoreSnapshot() || null != dynamicJoin)
        {
            return;
        }

        if (NULL_VALUE == this.memberId)
        {
            this.memberId = memberId;
            ctx.clusterMarkFile().memberId(memberId);
        }

        if (ClusterMember.EMPTY_CLUSTER_MEMBER_ARRAY == clusterMembers)
        {
            clusterMembers = ClusterMember.parse(members);
            this.highMemberId = Math.max(ClusterMember.highMemberId(clusterMembers), highMemberId);
            rankedPositions = new long[ClusterMember.quorumThreshold(clusterMembers.length)];
            thisMember = clusterMemberByIdMap.get(memberId);

            final ChannelUri memberStatusUri = ChannelUri.parse(ctx.memberStatusChannel());
            memberStatusUri.put(ENDPOINT_PARAM_NAME, thisMember.memberFacingEndpoint());

            ClusterMember.addMemberStatusPublications(
                clusterMembers, thisMember, memberStatusUri, ctx.memberStatusStreamId(), aeron);
        }
    }

    Publication addNewLogPublication()
    {
        closeExistingLog();

        final ExclusivePublication publication = createLogPublication(recoveryPlan, election.logPosition());
        logPublisher.publication(publication);

        return publication;
    }

    void becomeLeader(
        final long leadershipTermId, final long logPosition, final int logSessionId, final boolean isStartup)
    {
        leadershipTermId(leadershipTermId);

        final ChannelUri channelUri = ChannelUri.parse(ctx.logChannel());
        channelUri.put(SESSION_ID_PARAM_NAME, Integer.toString(logSessionId));
        channelUri.put(TAGS_PARAM_NAME, Long.toString(logPublicationChannelTag));
        channelUri.put(ALIAS_PARAM_NAME, "log");

        final String recordingChannel = channelUri.toString();
        startLogRecording(recordingChannel, SourceLocation.LOCAL);
        createAppendPosition(logSessionId);

        final String logChannel = channelUri.isUdp() ? SPY_PREFIX + recordingChannel : recordingChannel;
        awaitServicesReady(logChannel, logSessionId, logPosition, isStartup);

        if (!isStartup)
        {
            for (final ClusterSession session : sessionByIdMap.values())
            {
                if (session.state() != CLOSED)
                {
                    session.connect(aeron);
                }
            }

            final long nowNs = clusterClock.timeNanos();
            for (final ClusterSession session : sessionByIdMap.values())
            {
                if (session.state() != CLOSED)
                {
                    session.timeOfLastActivityNs(nowNs);
                    session.hasNewLeaderEventPending(true);
                }
            }
        }
    }

    void notifiedCommitPosition(final long logPosition)
    {
        notifiedCommitPosition = logPosition;
    }

    void updateMemberDetails(final Election election)
    {
        leaderMember = election.leader();
        sessionProxy.leaderMemberId(leaderMember.id()).leadershipTermId(leadershipTermId);

        for (final ClusterMember clusterMember : clusterMembers)
        {
            clusterMember.isLeader(clusterMember.id() == leaderMember.id());
        }

        clientFacingEndpoints = ClusterMember.clientFacingEndpoints(clusterMembers);
    }

    void liveLogDestination(final String liveLogDestination)
    {
        this.liveLogDestination = liveLogDestination;
    }

    void replayLogDestination(final String replayLogDestination)
    {
        this.replayLogDestination = replayLogDestination;
    }

    boolean hasReplayDestination()
    {
        return null != replayLogDestination;
    }

    Subscription createAndRecordLogSubscriptionAsFollower(final String logChannel)
    {
        closeExistingLog();

        final Subscription subscription = aeron.addSubscription(logChannel, ctx.logStreamId());
        startLogRecording(logChannel, SourceLocation.REMOTE);

        return subscription;
    }

    void appendDynamicJoinTermAndSnapshots()
    {
        if (!dynamicJoinSnapshots.isEmpty())
        {
            final long logRecordingId = logRecordingId();
            final RecordingLog.Snapshot lastSnapshot = dynamicJoinSnapshots.get(dynamicJoinSnapshots.size() - 1);

            recordingLog.appendTerm(
                logRecordingId,
                lastSnapshot.leadershipTermId,
                lastSnapshot.termBaseLogPosition,
                lastSnapshot.timestamp);

            for (int i = dynamicJoinSnapshots.size() - 1; i >= 0; i--)
            {
                final RecordingLog.Snapshot snapshot = dynamicJoinSnapshots.get(i);

                recordingLog.appendSnapshot(
                    snapshot.recordingId,
                    snapshot.leadershipTermId,
                    snapshot.termBaseLogPosition,
                    snapshot.logPosition,
                    snapshot.timestamp,
                    snapshot.serviceId);
            }

            dynamicJoinSnapshots.clear();
        }
    }

    boolean findLogImage(final Subscription subscription, final int logSessionId)
    {
        boolean result = false;

        if (null == logAdapter.image())
        {
            final Image image = subscription.imageBySessionId(logSessionId);
            if (null != image)
            {
                logAdapter.image(image);
                lastAppendPosition = 0;
                createAppendPosition(logSessionId);
                appendDynamicJoinTermAndSnapshots();

                result = true;
            }
        }
        else
        {
            result = true;
        }

        return result;
    }

    void awaitFollowerLogImage(final Subscription subscription, final int logSessionId)
    {
        leadershipTermId(election.leadershipTermId());
        idleStrategy.reset();
        while (!findLogImage(subscription, logSessionId))
        {
            idle();
        }
    }

    void awaitServicesReady(
        final String logChannel, final int logSessionId, final long logPosition, final boolean isStartup)
    {
        serviceProxy.joinLog(
            leadershipTermId,
            logPosition,
            Long.MAX_VALUE,
            memberId,
            logSessionId,
            ctx.logStreamId(),
            isStartup,
            logChannel);

        if (isStartup)
        {
            for (final ClusterSession session : sessionByIdMap.values())
            {
                if (session.state() == OPEN)
                {
                    session.close(CloseReason.TIMEOUT, ctx.countedErrorHandler());
                }
            }
        }

        awaitServicesAt(logPosition);
    }

    LogReplay newLogReplay(final long electionPosition)
    {
        if (null != recoveryPlan.log)
        {
            final RecordingLog.Log log = recoveryPlan.log;
            final long stopPosition = min(log.stopPosition, electionPosition);

            if (recoveryPlan.hasReplay())
            {
                return new LogReplay(
                    archive,
                    log.recordingId,
                    log.startPosition,
                    stopPosition,
                    log.leadershipTermId,
                    log.sessionId,
                    logAdapter,
                    ctx);
            }
        }

        return null;
    }

    void awaitServicesReadyForReplay(
        final String channel,
        final int streamId,
        final int logSessionId,
        final long leadershipTermId,
        final long logPosition,
        final long maxLogPosition)
    {
        serviceProxy.joinLog(
            leadershipTermId, logPosition, maxLogPosition, memberId, logSessionId, streamId, true, channel);
        awaitServicesAt(logPosition);
    }

    void awaitServicesReplayPosition(final long logPosition)
    {
        awaitServicesAt(logPosition);
    }

    void replayLogPoll(final LogAdapter logAdapter, final long stopPosition)
    {
        if (ConsensusModule.State.ACTIVE == state || ConsensusModule.State.SUSPENDED == state)
        {
            final int workCount = logAdapter.poll(stopPosition);
            final long logPosition = logAdapter.position();
            if (0 == workCount)
            {
                if (logAdapter.isImageClosed() && logPosition != stopPosition)
                {
                    throw new ClusterException("unexpected image close when replaying log: position=");
                }
            }
            else
            {
                commitPosition.setOrdered(logPosition);
            }
        }

        consensusModuleAdapter.poll();
    }

    long logRecordingId()
    {
        if (null != recoveryPlan.log)
        {
            return recoveryPlan.log.recordingId;
        }

        if (null == appendPosition)
        {
            return NULL_VALUE;
        }

        return RecordingPos.getRecordingId(aeron.countersReader(), appendPosition.counterId());
    }

    void truncateLogEntry(final long leadershipTermId, final long logPosition)
    {
        archive.truncateRecording(logRecordingId(), logPosition);
        recordingLog.commitLogPosition(leadershipTermId, logPosition);
    }

    public void trackCatchupCompletion(final ClusterMember follower, final long leadershipTermId)
    {
        if (NULL_VALUE != follower.catchupReplaySessionId())
        {
            if (follower.logPosition() >= logPublisher.position())
            {
                if (NULL_VALUE != follower.catchupReplayCorrelationId())
                {
                    if (archive.archiveProxy().stopReplay(
                        follower.catchupReplaySessionId(),
                        aeron.nextCorrelationId(),
                        archive.controlSessionId()))
                    {
                        follower.catchupReplayCorrelationId(NULL_VALUE);
                    }
                }

                if (memberStatusPublisher.stopCatchup(follower.publication(), leadershipTermId, follower.id()))
                {
                    follower.catchupReplaySessionId(NULL_VALUE);
                }
            }
        }
    }

    boolean electionComplete()
    {
        final long termBaseLogPosition = election.logPosition();
        final long now = clusterClock.time();
        final long nowNs = clusterTimeUnit.toNanos(now);

        if (Cluster.Role.LEADER == role)
        {
            if (!logPublisher.appendNewLeadershipTermEvent(
                leadershipTermId,
                now,
                termBaseLogPosition,
                memberId,
                logPublisher.sessionId(),
                clusterTimeUnit,
                ctx.appVersion()))
            {
                return false;
            }

            timeOfLastLogUpdateNs = nowNs - leaderHeartbeatIntervalNs;
            timerService.currentTickTime(now);
            ClusterControl.ToggleState.activate(controlToggle);
        }
        else
        {
            timeOfLastLogUpdateNs = nowNs;
            timeOfLastAppendPositionNs = nowNs;
        }

        recoveryPlan = recordingLog.createRecoveryPlan(archive, ctx.serviceCount());
        election = null;
        notifiedCommitPosition = termBaseLogPosition;
        commitPosition.setOrdered(termBaseLogPosition);
        pendingServiceMessages.consume(followerServiceSessionMessageSweeper, Integer.MAX_VALUE);

        if (!ctx.ingressChannel().contains(ENDPOINT_PARAM_NAME))
        {
            final ChannelUri ingressUri = ChannelUri.parse(ctx.ingressChannel());
            ingressUri.put(ENDPOINT_PARAM_NAME, thisMember.clientFacingEndpoint());

            ingressAdapter.connect(aeron.addSubscription(
                ingressUri.toString(), ctx.ingressStreamId(), null, this::onUnavailableIngressImage));
        }
        else if (Cluster.Role.LEADER == role)
        {
            ingressAdapter.connect(aeron.addSubscription(
                ctx.ingressChannel(), ctx.ingressStreamId(), null, this::onUnavailableIngressImage));
        }

        return true;
    }

    boolean dynamicJoinComplete()
    {
        if (0 == clusterMembers.length)
        {
            clusterMembers = dynamicJoin.clusterMembers();
            ClusterMember.addClusterMemberIds(clusterMembers, clusterMemberByIdMap);
            leaderMember = dynamicJoin.leader();

            final ChannelUri memberStatusUri = ChannelUri.parse(ctx.memberStatusChannel());
            ClusterMember.addMemberStatusPublications(
                clusterMembers, thisMember, memberStatusUri, ctx.memberStatusStreamId(), aeron);
        }

        if (NULL_VALUE == memberId)
        {
            memberId = dynamicJoin.memberId();
            ctx.clusterMarkFile().memberId(memberId);
            thisMember.id(memberId);
        }

        dynamicJoin = null;

        election = new Election(
            false,
            leadershipTermId,
            recoveryPlan.appendedLogPosition,
            clusterMembers,
            clusterMemberByIdMap,
            thisMember,
            memberStatusAdapter,
            memberStatusPublisher,
            ctx,
            this);

        return true;
    }

    int catchupPoll(final Subscription subscription, final int logSessionId, final long limitPosition, final long nowNs)
    {
        int workCount = 0;
        if (!findLogImage(subscription, logSessionId))
        {
            return workCount;
        }

        if (ConsensusModule.State.ACTIVE == state || ConsensusModule.State.SUSPENDED == state)
        {
            final Image image = logAdapter.image();
            final int fragmentsPolled = logAdapter.poll(Math.min(appendPosition.get(), limitPosition));
            if (fragmentsPolled == 0 && image.isClosed())
            {
                throw new ClusterException("unexpected image close replaying log at position " + image.position());
            }
            workCount += fragmentsPolled;
        }

        final long appendPosition = logAdapter.position();
        if (appendPosition != lastAppendPosition)
        {
            commitPosition.setOrdered(appendPosition);
            final ExclusivePublication publication = election.leader().publication();
            if (memberStatusPublisher.appendPosition(publication, replayLeadershipTermId, appendPosition, memberId))
            {
                lastAppendPosition = appendPosition;
                timeOfLastAppendPositionNs = nowNs;
            }
        }

        workCount += consensusModuleAdapter.poll();

        return workCount;
    }

    boolean hasAppendReachedPosition(final Subscription subscription, final int logSessionId, final long position)
    {
        return findLogImage(subscription, logSessionId) && commitPosition.getWeak() >= position;
    }

    boolean hasAppendReachedLivePosition(final Subscription subscription, final int logSessionId, final long position)
    {
        boolean result = false;

        if (findLogImage(subscription, logSessionId))
        {
            final long localPosition = commitPosition.getWeak();
            final long window = logAdapter.image().termBufferLength() * 2L;

            result = localPosition >= (position - window);
        }

        return result;
    }

    void catchupInitiated(final long nowNs)
    {
        timeOfLastAppendPositionNs = nowNs;
    }

    boolean hasCatchupStalled(final long nowNs, final long catchupTimeoutNs)
    {
        return nowNs > (timeOfLastAppendPositionNs + catchupTimeoutNs);
    }

    void stopAllCatchups()
    {
        for (final ClusterMember member : clusterMembers)
        {
            if (member.catchupReplaySessionId() != NULL_VALUE)
            {
                if (member.catchupReplayCorrelationId() != NULL_VALUE)
                {
                    try
                    {
                        archive.stopReplay(member.catchupReplaySessionId());
                    }
                    catch (final Exception ex)
                    {
                        ctx.countedErrorHandler().onError(ex);
                    }
                }

                member.catchupReplaySessionId(NULL_VALUE);
                member.catchupReplayCorrelationId(NULL_VALUE);
            }
        }
    }

    void retrievedSnapshot(final long localRecordingId, final RecordingLog.Snapshot leaderSnapshot)
    {
        dynamicJoinSnapshots.add(new RecordingLog.Snapshot(
            localRecordingId,
            leaderSnapshot.leadershipTermId,
            leaderSnapshot.termBaseLogPosition,
            leaderSnapshot.logPosition,
            leaderSnapshot.timestamp,
            leaderSnapshot.serviceId));
    }

    Counter loadSnapshotsFromDynamicJoin()
    {
        recoveryPlan = RecordingLog.createRecoveryPlan(dynamicJoinSnapshots);

        final Counter recoveryStateCounter = addRecoveryStateCounter(recoveryPlan);
        if (!recoveryPlan.snapshots.isEmpty())
        {
            recoverFromSnapshot(recoveryPlan.snapshots.get(0), archive);
        }

        return recoveryStateCounter;
    }

    boolean pollForEndOfSnapshotLoad(final Counter recoveryStateCounter, final long nowNs)
    {
        consensusModuleAdapter.poll();

        if (ServiceAck.hasReachedPosition(expectedAckPosition, serviceAckId, serviceAckQueues))
        {
            captureServiceClientIds();
            ++serviceAckId;

            CloseHelper.close(ctx.countedErrorHandler(), recoveryStateCounter);
            if (ConsensusModule.State.SUSPENDED != state)
            {
                state(ConsensusModule.State.ACTIVE);
            }

            timeOfLastLogUpdateNs = nowNs;
            leadershipTermId(recoveryPlan.lastLeadershipTermId);

            return true;
        }

        return false;
    }

    private int slowTickWork(final long nowMs, final long nowNs)
    {
        if (ConsensusModule.State.CLOSED == state)
        {
            throw new AgentTerminationException("module is closed");
        }

        int workCount = aeronClientInvoker.invoke();
        if (aeron.isClosed())
        {
            throw new AgentTerminationException("unexpected Aeron close");
        }

        if (null != archive)
        {
            checkForArchiveErrors();
        }

        if (nowNs >= (timeOfLastMarkFileUpdateNs + MARK_FILE_UPDATE_INTERVAL_NS))
        {
            markFile.updateActivityTimestamp(nowMs);
            timeOfLastMarkFileUpdateNs = nowMs;
        }

        workCount += processRedirectSessions(redirectSessions, nowNs);
        workCount += processRejectedSessions(rejectedSessions, nowNs);

        if (null == election)
        {
            if (Cluster.Role.LEADER == role)
            {
                workCount += checkControlToggle(nowNs);

                if (ConsensusModule.State.ACTIVE == state)
                {
                    workCount += processPendingSessions(pendingSessions, nowMs, nowNs);
                    workCount += checkSessions(sessionByIdMap, nowNs);
                    workCount += processPassiveMembers(passiveMembers);

                    if (!ClusterMember.hasActiveQuorum(clusterMembers, nowNs, leaderHeartbeatTimeoutNs))
                    {
                        ctx.countedErrorHandler().onError(new ClusterException(
                            "inactive follower quorum", AeronException.Category.WARN));
                        enterElection(nowNs);
                        workCount += 1;
                    }
                }
                else if (ConsensusModule.State.TERMINATING == state)
                {
                    if (clusterTermination.canTerminate(clusterMembers, terminationPosition, nowNs))
                    {
                        recordingLog.commitLogPosition(leadershipTermId, terminationPosition);
                        state(ConsensusModule.State.CLOSED);
                        ctx.terminationHook().run();
                    }
                }
            }
            else if (ConsensusModule.State.ACTIVE == state || ConsensusModule.State.SUSPENDED == state)
            {
                if (NULL_POSITION != terminationPosition && logAdapter.position() >= terminationPosition)
                {
                    serviceProxy.terminationPosition(terminationPosition);
                    state(ConsensusModule.State.TERMINATING);
                }

                if (nowNs >= (timeOfLastLogUpdateNs + leaderHeartbeatTimeoutNs))
                {
                    ctx.countedErrorHandler().onError(new ClusterException(
                        "leader heartbeat timeout", AeronException.Category.WARN));
                    enterElection(nowNs);
                    workCount += 1;
                }
            }
        }

        return workCount;
    }

    private void checkForArchiveErrors()
    {
        final ControlResponsePoller controlResponsePoller = archive.controlResponsePoller();
        if (!controlResponsePoller.subscription().isConnected())
        {
            ctx.countedErrorHandler().onError(new ClusterException(
                "local archive not connected", AeronException.Category.FATAL));
        }
        else if (controlResponsePoller.poll() != 0 && controlResponsePoller.isPollComplete())
        {
            if (controlResponsePoller.controlSessionId() == archive.controlSessionId() &&
                controlResponsePoller.code() == ControlResponseCode.ERROR)
            {
                for (final ClusterMember member : clusterMembers)
                {
                    if (member.catchupReplayCorrelationId() != NULL_VALUE &&
                        member.catchupReplayCorrelationId() == controlResponsePoller.correlationId())
                    {
                        member.catchupReplaySessionId(NULL_VALUE);
                        member.catchupReplayCorrelationId(NULL_VALUE);

                        ctx.countedErrorHandler().onError(new ClusterException(
                            "catchup replay failed - " + controlResponsePoller.errorMessage(),
                            AeronException.Category.WARN));
                        return;
                    }
                }

                ctx.countedErrorHandler().onError(new ArchiveException(
                    controlResponsePoller.errorMessage(),
                    (int)controlResponsePoller.relevantId(),
                    controlResponsePoller.correlationId()));
            }
        }
    }

    private int consensusWork(final long timestamp, final long nowNs)
    {
        int workCount = 0;

        if (Cluster.Role.LEADER == role && ConsensusModule.State.ACTIVE == state)
        {
            workCount += timerService.poll(timestamp);
            workCount += pendingServiceMessages.forEach(
                pendingServiceMessageHeadOffset, serviceSessionMessageAppender, SERVICE_MESSAGE_LIMIT);
            workCount += ingressAdapter.poll();
        }
        else if (Cluster.Role.FOLLOWER == role &&
            (ConsensusModule.State.ACTIVE == state || ConsensusModule.State.SUSPENDED == state))
        {
            workCount += ingressAdapter.poll();

            final int count = logAdapter.poll(min(notifiedCommitPosition, appendPosition.get()));
            if (0 == count && logAdapter.isImageClosed())
            {
                ctx.countedErrorHandler().onError(new ClusterException(
                    "log disconnected from leader: logPosition=" + logAdapter.position() +
                    " commitPosition=" + commitPosition.getWeak() +
                    " leadershipTermId=" + leadershipTermId +
                    " leaderId=" + leaderMember.id(),
                    AeronException.Category.WARN));
                enterElection(nowNs);
                return 1;
            }

            workCount += count;
        }

        workCount += memberStatusAdapter.poll();
        workCount += updateMemberPosition(nowNs);
        workCount += consensusModuleAdapter.poll();

        return workCount;
    }

    private int checkControlToggle(final long nowNs)
    {
        switch (ClusterControl.ToggleState.get(controlToggle))
        {
            case SUSPEND:
                if (ConsensusModule.State.ACTIVE == state && appendAction(ClusterAction.SUSPEND))
                {
                    state(ConsensusModule.State.SUSPENDED);
                }
                break;

            case RESUME:
                if (ConsensusModule.State.SUSPENDED == state && appendAction(ClusterAction.RESUME))
                {
                    state(ConsensusModule.State.ACTIVE);
                    ClusterControl.ToggleState.reset(controlToggle);
                }
                break;

            case SNAPSHOT:
                if (ConsensusModule.State.ACTIVE == state && appendAction(ClusterAction.SNAPSHOT))
                {
                    state(ConsensusModule.State.SNAPSHOT);
                }
                break;

            case SHUTDOWN:
                if (ConsensusModule.State.ACTIVE == state && appendAction(ClusterAction.SNAPSHOT))
                {
                    final long position = logPublisher.position();
                    clusterTermination = new ClusterTermination(nowNs + ctx.terminationTimeoutNs());
                    clusterTermination.terminationPosition(memberStatusPublisher, clusterMembers, thisMember, position);
                    terminationPosition = position;
                    state(ConsensusModule.State.SNAPSHOT);
                }
                break;

            case ABORT:
                if (ConsensusModule.State.ACTIVE == state)
                {
                    final long position = logPublisher.position();
                    clusterTermination = new ClusterTermination(nowNs + ctx.terminationTimeoutNs());
                    clusterTermination.terminationPosition(memberStatusPublisher, clusterMembers, thisMember, position);
                    terminationPosition = position;
                    serviceProxy.terminationPosition(terminationPosition);
                    state(ConsensusModule.State.TERMINATING);
                }
                break;

            default:
                return 0;
        }

        return 1;
    }

    private boolean appendAction(final ClusterAction action)
    {
        return logPublisher.appendClusterAction(leadershipTermId, clusterClock.time(), action);
    }

    private int processPendingSessions(
        final ArrayList<ClusterSession> pendingSessions, final long nowMs, final long nowNs)
    {
        int workCount = 0;

        for (int lastIndex = pendingSessions.size() - 1, i = lastIndex; i >= 0; i--)
        {
            final ClusterSession session = pendingSessions.get(i);

            if (session.state() == INIT || session.state() == CONNECTED)
            {
                if (session.isResponsePublicationConnected())
                {
                    session.state(CONNECTED);
                    authenticator.onConnectedSession(sessionProxy.session(session), nowMs);
                }
            }

            if (session.state() == CHALLENGED)
            {
                if (session.isResponsePublicationConnected())
                {
                    authenticator.onChallengedSession(sessionProxy.session(session), nowMs);
                }
            }

            if (session.state() == AUTHENTICATED)
            {
                if (session.isBackupSession())
                {
                    if (session.responsePublication().isConnected())
                    {
                        final RecordingLog.Entry lastEntry = recordingLog.findLastTerm();

                        if (memberStatusPublisher.backupResponse(
                            session.responsePublication(),
                            session.correlationId(),
                            recoveryPlan.log.recordingId,
                            recoveryPlan.log.leadershipTermId,
                            recoveryPlan.log.termBaseLogPosition,
                            lastEntry.leadershipTermId,
                            lastEntry.termBaseLogPosition,
                            commitPosition.id(),
                            leaderMember.id(),
                            recoveryPlan,
                            ClusterMember.encodeAsString(clusterMembers)))
                        {
                            ArrayListUtil.fastUnorderedRemove(pendingSessions, i, lastIndex--);
                            session.close(ctx.countedErrorHandler());
                        }
                    }
                }
                else
                {
                    ArrayListUtil.fastUnorderedRemove(pendingSessions, i, lastIndex--);
                    session.timeOfLastActivityNs(nowNs);
                    sessionByIdMap.put(session.id(), session);
                    appendSessionOpen(session);
                }

                workCount += 1;
            }
            else if (session.state() == REJECTED)
            {
                ArrayListUtil.fastUnorderedRemove(pendingSessions, i, lastIndex--);
                rejectedSessions.add(session);
            }
            else if (nowNs > (session.timeOfLastActivityNs() + sessionTimeoutNs))
            {
                ArrayListUtil.fastUnorderedRemove(pendingSessions, i, lastIndex--);
                session.close(ctx.countedErrorHandler());
                ctx.timedOutClientCounter().incrementOrdered();
            }
        }

        return workCount;
    }

    private int processRejectedSessions(final ArrayList<ClusterSession> rejectedSessions, final long nowNs)
    {
        int workCount = 0;

        for (int lastIndex = rejectedSessions.size() - 1, i = lastIndex; i >= 0; i--)
        {
            final ClusterSession session = rejectedSessions.get(i);
            final String detail = session.responseDetail();
            final EventCode eventCode = session.eventCode();

            if (egressPublisher.sendEvent(session, leadershipTermId, leaderMember.id(), eventCode, detail) ||
                nowNs > (session.timeOfLastActivityNs() + sessionTimeoutNs))
            {
                ArrayListUtil.fastUnorderedRemove(rejectedSessions, i, lastIndex--);
                session.close(ctx.countedErrorHandler());
                workCount++;
            }
        }

        return workCount;
    }

    private int processRedirectSessions(final ArrayList<ClusterSession> redirectSessions, final long nowNs)
    {
        int workCount = 0;

        for (int lastIndex = redirectSessions.size() - 1, i = lastIndex; i >= 0; i--)
        {
            final ClusterSession session = redirectSessions.get(i);
            final EventCode eventCode = EventCode.REDIRECT;
            final int leaderId = leaderMember.id();

            if (egressPublisher.sendEvent(session, leadershipTermId, leaderId, eventCode, clientFacingEndpoints) ||
                nowNs > (session.timeOfLastActivityNs() + sessionTimeoutNs))
            {
                ArrayListUtil.fastUnorderedRemove(redirectSessions, i, lastIndex--);
                session.close(ctx.countedErrorHandler());
                workCount++;
            }
        }

        return workCount;
    }

    private int processPassiveMembers(final ClusterMember[] passiveMembers)
    {
        int workCount = 0;

        for (final ClusterMember member : passiveMembers)
        {
            if (member.correlationId() != NULL_VALUE)
            {
                if (memberStatusPublisher.clusterMemberChange(
                    member.publication(),
                    member.correlationId(),
                    leaderMember.id(),
                    ClusterMember.encodeAsString(clusterMembers),
                    ClusterMember.encodeAsString(passiveMembers)))
                {
                    member.correlationId(NULL_VALUE);
                    workCount++;
                }
            }
            else if (member.hasRequestedJoin() && member.logPosition() == logPublisher.position())
            {
                final ClusterMember[] newMembers = ClusterMember.addMember(clusterMembers, member);
                final long now = clusterClock.time();

                if (logPublisher.appendMembershipChangeEvent(
                    this.leadershipTermId,
                    now,
                    thisMember.id(),
                    newMembers.length,
                    ChangeType.JOIN,
                    member.id(),
                    ClusterMember.encodeAsString(newMembers)) > 0)
                {
                    timeOfLastLogUpdateNs = clusterTimeUnit.toNanos(now) - leaderHeartbeatIntervalNs;
                    this.passiveMembers = ClusterMember.removeMember(this.passiveMembers, member.id());
                    clusterMembers = newMembers;
                    rankedPositions = new long[ClusterMember.quorumThreshold(clusterMembers.length)];
                    member.hasRequestedJoin(false);

                    workCount++;
                    break;
                }
            }
        }

        return workCount;
    }

    private int checkSessions(final Long2ObjectHashMap<ClusterSession> sessionByIdMap, final long nowNs)
    {
        int workCount = 0;

        for (final Iterator<ClusterSession> i = sessionByIdMap.values().iterator(); i.hasNext(); )
        {
            final ClusterSession session = i.next();

            if (nowNs > (session.timeOfLastActivityNs() + sessionTimeoutNs))
            {
                if (session.state() == OPEN)
                {
                    session.close(CloseReason.TIMEOUT, ctx.countedErrorHandler());
                    if (logPublisher.appendSessionClose(session, leadershipTermId, clusterClock.time()))
                    {
                        final String msg = session.closeReason().name();
                        egressPublisher.sendEvent(session, leadershipTermId, memberId, EventCode.CLOSED, msg);
                        session.closedLogPosition(logPublisher.position());
                        uncommittedClosedSessions.addLast(session);
                        i.remove();
                        ctx.timedOutClientCounter().incrementOrdered();
                    }
                }
                else if (session.state() == CLOSED)
                {
                    if (logPublisher.appendSessionClose(session, leadershipTermId, clusterClock.time()))
                    {
                        final String msg = session.closeReason().name();
                        egressPublisher.sendEvent(session, leadershipTermId, memberId, EventCode.CLOSED, msg);
                        session.closedLogPosition(logPublisher.position());
                        uncommittedClosedSessions.addLast(session);
                        i.remove();

                        if (session.closeReason() == CloseReason.TIMEOUT)
                        {
                            ctx.timedOutClientCounter().incrementOrdered();
                        }
                    }
                }
                else
                {
                    i.remove();
                    session.close(ctx.countedErrorHandler());
                }

                workCount += 1;
            }
            else if (session.state() == CONNECTED)
            {
                appendSessionOpen(session);
                workCount += 1;
            }
            else if (session.hasNewLeaderEventPending())
            {
                sendNewLeaderEvent(session);
                workCount += 1;
            }
        }

        return workCount;
    }

    private void sendNewLeaderEvent(final ClusterSession session)
    {
        if (egressPublisher.newLeader(session, leadershipTermId, leaderMember.id(), clientFacingEndpoints))
        {
            session.hasNewLeaderEventPending(false);
        }
    }

    private void appendSessionOpen(final ClusterSession session)
    {
        final long resultingPosition = logPublisher.appendSessionOpen(session, leadershipTermId, clusterClock.time());
        if (resultingPosition > 0)
        {
            session.open(resultingPosition);
        }
    }

    private void createAppendPosition(final int logSessionId)
    {
        final CountersReader counters = aeron.countersReader();
        final int recordingCounterId = awaitRecordingCounter(counters, logSessionId);

        appendPosition = new ReadableCounter(counters, recordingCounterId);
    }

    private void leadershipTermId(final long leadershipTermId)
    {
        this.leadershipTermId = leadershipTermId;
        this.replayLeadershipTermId = leadershipTermId;
    }

    private void recoverFromSnapshot(final RecordingLog.Snapshot snapshot, final AeronArchive archive)
    {
        final String channel = ctx.replayChannel();
        final int streamId = ctx.replayStreamId();
        final int sessionId = (int)archive.startReplay(snapshot.recordingId, 0, NULL_LENGTH, channel, streamId);
        final String replaySubscriptionChannel = ChannelUri.addSessionId(channel, sessionId);

        try (Subscription subscription = aeron.addSubscription(replaySubscriptionChannel, streamId))
        {
            final Image image = awaitImage(sessionId, subscription);
            final ConsensusModuleSnapshotLoader snapshotLoader = new ConsensusModuleSnapshotLoader(image, this);

            while (true)
            {
                final int fragments = snapshotLoader.poll();
                if (fragments == 0)
                {
                    if (snapshotLoader.isDone())
                    {
                        break;
                    }

                    if (image.isClosed())
                    {
                        throw new ClusterException("snapshot ended unexpectedly");
                    }
                }

                idle(fragments);
            }

            final int appVersion = snapshotLoader.appVersion();
            if (SemanticVersion.major(ctx.appVersion()) != SemanticVersion.major(appVersion))
            {
                throw new ClusterException(
                    "incompatible version: " + SemanticVersion.toString(ctx.appVersion()) +
                    " snapshot=" + SemanticVersion.toString(appVersion));
            }

            final TimeUnit timeUnit = snapshotLoader.timeUnit();
            if (timeUnit != clusterTimeUnit)
            {
                throw new ClusterException("incompatible time unit: " + clusterTimeUnit + " snapshot=" + timeUnit);
            }

            pendingServiceMessages.forEach(this::serviceSessionMessageReset, Integer.MAX_VALUE);
        }

        timerService.currentTickTime(clusterClock.time());
        leadershipTermId(snapshot.leadershipTermId);
        expectedAckPosition = snapshot.logPosition;
    }

    private Image awaitImage(final int sessionId, final Subscription subscription)
    {
        idleStrategy.reset();
        Image image;
        while ((image = subscription.imageBySessionId(sessionId)) == null)
        {
            idle();
        }

        return image;
    }

    private Counter addRecoveryStateCounter(final RecordingLog.RecoveryPlan plan)
    {
        final int snapshotsCount = plan.snapshots.size();

        if (snapshotsCount > 0)
        {
            final long[] serviceSnapshotRecordingIds = new long[snapshotsCount - 1];
            final RecordingLog.Snapshot snapshot = plan.snapshots.get(0);

            for (int i = 1; i < snapshotsCount; i++)
            {
                final RecordingLog.Snapshot serviceSnapshot = plan.snapshots.get(i);
                serviceSnapshotRecordingIds[serviceSnapshot.serviceId] = serviceSnapshot.recordingId;
            }

            return RecoveryState.allocate(
                aeron,
                tempBuffer,
                snapshot.leadershipTermId,
                snapshot.logPosition,
                snapshot.timestamp,
                plan.hasReplay(),
                serviceSnapshotRecordingIds);
        }

        return RecoveryState.allocate(aeron, tempBuffer, leadershipTermId, 0, 0, plan.hasReplay());
    }

    private DynamicJoin requiresDynamicJoin()
    {
        if (0 == clusterMembers.length && null != ctx.clusterMembersStatusEndpoints())
        {
            return new DynamicJoin(
                ctx.clusterMembersStatusEndpoints(),
                archive,
                memberStatusAdapter,
                memberStatusPublisher,
                ctx,
                this);
        }

        return null;
    }

    private void awaitServicesAt(final long logPosition)
    {
        expectedAckPosition = logPosition;

        while (!ServiceAck.hasReachedPosition(logPosition, serviceAckId, serviceAckQueues))
        {
            idle(consensusModuleAdapter.poll());
        }

        ServiceAck.removeHead(serviceAckQueues);
        ++serviceAckId;
    }

    private void captureServiceClientIds()
    {
        for (int i = 0, length = serviceClientIds.length; i < length; i++)
        {
            final ServiceAck serviceAck = serviceAckQueues[i].pollFirst();
            serviceClientIds[i] = Objects.requireNonNull(serviceAck).relevantId();
        }
    }

    private void handleMemberRemovals(final long commitPosition)
    {
        ClusterMember[] newClusterMembers = clusterMembers;

        for (final ClusterMember member : clusterMembers)
        {
            if (member.hasRequestedRemove() && member.removalPosition() <= commitPosition)
            {
                if (member == thisMember)
                {
                    state(ConsensusModule.State.QUITTING);
                }

                newClusterMembers = ClusterMember.removeMember(newClusterMembers, member.id());
                clusterMemberByIdMap.remove(member.id());
                clusterMemberByIdMap.compact();

                member.closePublication(ctx.countedErrorHandler());

                logPublisher.removePassiveFollower(member.logEndpoint());
                pendingMemberRemovals--;
            }
        }

        clusterMembers = newClusterMembers;
        rankedPositions = new long[ClusterMember.quorumThreshold(clusterMembers.length)];
    }

    private int updateMemberPosition(final long nowNs)
    {
        int workCount = 0;

        final long appendPosition = this.appendPosition.get();
        if (Cluster.Role.LEADER == role)
        {
            thisMember.logPosition(appendPosition).timeOfLastAppendPositionNs(nowNs);
            final long commitPosition = min(quorumPosition(clusterMembers, rankedPositions), appendPosition);

            if (this.commitPosition.proposeMaxOrdered(commitPosition) ||
                nowNs >= (timeOfLastLogUpdateNs + leaderHeartbeatIntervalNs))
            {
                for (final ClusterMember member : clusterMembers)
                {
                    if (member != thisMember)
                    {
                        final ExclusivePublication publication = member.publication();
                        memberStatusPublisher.commitPosition(publication, leadershipTermId, commitPosition, memberId);
                    }
                }

                timeOfLastLogUpdateNs = nowNs;

                if (pendingMemberRemovals > 0)
                {
                    handleMemberRemovals(commitPosition);
                }

                clearUncommittedEntriesTo(commitPosition);

                workCount += 1;
            }
        }
        else
        {
            final ExclusivePublication publication = leaderMember.publication();

            if ((appendPosition != lastAppendPosition ||
                nowNs >= (timeOfLastAppendPositionNs + leaderHeartbeatIntervalNs)) &&
                memberStatusPublisher.appendPosition(publication, leadershipTermId, appendPosition, memberId))
            {
                lastAppendPosition = appendPosition;
                timeOfLastAppendPositionNs = nowNs;
                workCount += 1;
            }

            commitPosition.proposeMaxOrdered(logAdapter.position());
        }

        return workCount;
    }

    private void clearUncommittedEntriesTo(final long commitPosition)
    {
        if (uncommittedServiceMessages > 0)
        {
            pendingServiceMessageHeadOffset -= pendingServiceMessages.consume(
                leaderServiceSessionMessageSweeper, Integer.MAX_VALUE);
        }

        while (uncommittedTimers.peekLong() <= commitPosition)
        {
            uncommittedTimers.pollLong();
            uncommittedTimers.pollLong();
        }

        while (true)
        {
            final ClusterSession clusterSession = uncommittedClosedSessions.peekFirst();
            if (null == clusterSession || clusterSession.closedLogPosition() > commitPosition)
            {
                break;
            }

            uncommittedClosedSessions.pollFirst();
        }
    }

    private void restoreUncommittedEntries(final long commitPosition)
    {
        for (final LongArrayQueue.LongIterator i = uncommittedTimers.iterator(); i.hasNext(); )
        {
            final long appendPosition = i.nextValue();
            final long correlationId = i.nextValue();

            if (appendPosition > commitPosition)
            {
                timerService.scheduleTimer(correlationId, timerService.currentTickTime());
            }
        }
        uncommittedTimers.clear();

        pendingServiceMessages.consume(followerServiceSessionMessageSweeper, Integer.MAX_VALUE);
        pendingServiceMessageHeadOffset = 0;

        if (uncommittedServiceMessages > 0)
        {
            pendingServiceMessages.consume(leaderServiceSessionMessageSweeper, Integer.MAX_VALUE);
            pendingServiceMessages.forEach(this::serviceSessionMessageReset, Integer.MAX_VALUE);
            uncommittedServiceMessages = 0;
        }

        ClusterSession session;
        while (null != (session = uncommittedClosedSessions.pollFirst()))
        {
            if (session.closedLogPosition() > commitPosition)
            {
                sessionByIdMap.put(session.id(), session);
            }
        }
    }

    private void enterElection(final long nowNs)
    {
        CloseHelper.close(ctx.countedErrorHandler(), ingressAdapter);

        final long commitPosition = this.commitPosition.getWeak();
        election = new Election(
            false,
            leadershipTermId,
            commitPosition,
            clusterMembers,
            clusterMemberByIdMap,
            thisMember,
            memberStatusAdapter,
            memberStatusPublisher,
            ctx,
            this);

        election.doWork(nowNs);
    }

    private void idle()
    {
        checkInterruptStatus();
        aeronClientInvoker.invoke();
        if (aeron.isClosed())
        {
            throw new AgentTerminationException();
        }

        idleStrategy.idle();
    }

    private void idle(final int workCount)
    {
        checkInterruptStatus();
        aeronClientInvoker.invoke();
        if (aeron.isClosed())
        {
            throw new AgentTerminationException();
        }

        idleStrategy.idle(workCount);
    }

    private static void checkInterruptStatus()
    {
        if (Thread.interrupted())
        {
            throw new AgentTerminationException("unexpected interrupt");
        }
    }

    private void takeSnapshot(final long timestamp, final long logPosition, final ServiceAck[] serviceAcks)
    {
        try
        {
            final long recordingId;
            try (ExclusivePublication publication = aeron.addExclusivePublication(
                ctx.snapshotChannel(), ctx.snapshotStreamId()))
            {
                final String channel = ChannelUri.addSessionId(ctx.snapshotChannel(), publication.sessionId());
                archive.startRecording(channel, ctx.snapshotStreamId(), LOCAL, true);
                final CountersReader counters = aeron.countersReader();
                final int counterId = awaitRecordingCounter(counters, publication.sessionId());
                recordingId = RecordingPos.getRecordingId(counters, counterId);

                snapshotState(publication, logPosition, replayLeadershipTermId);
                awaitRecordingComplete(recordingId, publication.position(), counters, counterId);
            }

            final long termBaseLogPosition = recordingLog.getTermEntry(replayLeadershipTermId).termBaseLogPosition;

            for (int serviceId = serviceAcks.length - 1; serviceId >= 0; serviceId--)
            {
                final long snapshotId = serviceAcks[serviceId].relevantId();
                recordingLog.appendSnapshot(
                    snapshotId, replayLeadershipTermId, termBaseLogPosition, logPosition, timestamp, serviceId);
            }

            recordingLog.appendSnapshot(
                recordingId, replayLeadershipTermId, termBaseLogPosition, logPosition, timestamp, SERVICE_ID);

            recordingLog.force(ctx.fileSyncLevel());
            recoveryPlan = recordingLog.createRecoveryPlan(archive, ctx.serviceCount());
            ctx.snapshotCounter().incrementOrdered();
        }
        catch (final Exception ex)
        {
            ctx.countedErrorHandler().onError(ex);
        }
    }

    private void awaitRecordingComplete(
        final long recordingId, final long position, final CountersReader counters, final int counterId)
    {
        idleStrategy.reset();
        do
        {
            idle();

            if (!RecordingPos.isActive(counters, counterId, recordingId))
            {
                throw new ClusterException("recording has stopped unexpectedly: " + recordingId);
            }
        }
        while (counters.getCounterValue(counterId) < position);
    }

    private int awaitRecordingCounter(final CountersReader counters, final int sessionId)
    {
        idleStrategy.reset();
        int counterId = RecordingPos.findCounterIdBySession(counters, sessionId);
        while (CountersReader.NULL_COUNTER_ID == counterId)
        {
            idle();
            counterId = RecordingPos.findCounterIdBySession(counters, sessionId);
        }

        return counterId;
    }

    private void snapshotState(
        final ExclusivePublication publication, final long logPosition, final long leadershipTermId)
    {
        final ConsensusModuleSnapshotTaker snapshotTaker = new ConsensusModuleSnapshotTaker(
            publication, idleStrategy, aeronClientInvoker);

        snapshotTaker.markBegin(SNAPSHOT_TYPE_ID, logPosition, leadershipTermId, 0, clusterTimeUnit, ctx.appVersion());

        snapshotTaker.snapshotConsensusModuleState(
            nextSessionId, nextServiceSessionId, logServiceSessionId, pendingServiceMessages.size());
        snapshotTaker.snapshotClusterMembers(memberId, highMemberId, clusterMembers);

        for (final ClusterSession session : sessionByIdMap.values())
        {
            if (session.state() == OPEN || session.state() == CLOSED)
            {
                snapshotTaker.snapshotSession(session);
            }
        }

        timerService.snapshot(snapshotTaker);
        snapshotTaker.snapshot(pendingServiceMessages);

        snapshotTaker.markEnd(SNAPSHOT_TYPE_ID, logPosition, leadershipTermId, 0, clusterTimeUnit, ctx.appVersion());
    }

    private ExclusivePublication createLogPublication(final RecordingLog.RecoveryPlan plan, final long position)
    {
        logPublicationTag = (int)aeron.nextCorrelationId();
        logPublicationChannelTag = (int)aeron.nextCorrelationId();

        final ChannelUri channelUri = ChannelUri.parse(ctx.logChannel());
        final boolean isMulticast = channelUri.containsKey(ENDPOINT_PARAM_NAME);

        if (!isMulticast && channelUri.isUdp())
        {
            channelUri.put(MDC_CONTROL_MODE_PARAM_NAME, MDC_CONTROL_MODE_MANUAL);
        }

        channelUri.put(ALIAS_PARAM_NAME, "log");
        channelUri.put(TAGS_PARAM_NAME, logPublicationChannelTag + "," + logPublicationTag);

        if (channelUri.isUdp() && !channelUri.containsKey(FLOW_CONTROL_PARAM_NAME))
        {
            final long timeout = Math.max(TimeUnit.NANOSECONDS.toSeconds(ctx.leaderHeartbeatTimeoutNs() / 2), 2L);
            final String fc = MinMulticastFlowControl.FC_PARAM_VALUE + ",t:" + timeout + "s";
            channelUri.put(FLOW_CONTROL_PARAM_NAME, fc);
        }

        if (null != plan.log)
        {
            channelUri.initialPosition(position, plan.log.initialTermId, plan.log.termBufferLength);
            channelUri.put(MTU_LENGTH_PARAM_NAME, Integer.toString(plan.log.mtuLength));
        }

        final String channel = channelUri.toString();
        final ExclusivePublication publication = aeron.addExclusivePublication(channel, ctx.logStreamId());

        if (!isMulticast)
        {
            for (final ClusterMember member : clusterMembers)
            {
                if (member != thisMember)
                {
                    publication.asyncAddDestination("aeron:udp?endpoint=" + member.logEndpoint());
                }
            }

            for (final ClusterMember member : passiveMembers)
            {
                publication.asyncAddDestination("aeron:udp?endpoint=" + member.logEndpoint());
            }
        }

        return publication;
    }

    private void startLogRecording(final String channel, final SourceLocation sourceLocation)
    {
        final long logRecordingId = recordingLog.findLastTermRecordingId();
        final int streamId = ctx.logStreamId();
        if (RecordingPos.NULL_RECORDING_ID == logRecordingId)
        {
            logSubscriptionId = archive.startRecording(channel, streamId, sourceLocation, true);
        }
        else
        {
            logSubscriptionId = archive.extendRecording(logRecordingId, channel, streamId, sourceLocation, true);
        }
    }

    private void clusterMemberJoined(final int memberId, final ClusterMember[] newMembers)
    {
        highMemberId = Math.max(highMemberId, memberId);

        final ClusterMember eventMember = ClusterMember.findMember(newMembers, memberId);
        if (null != eventMember)
        {
            if (null == eventMember.publication())
            {
                final ChannelUri memberStatusUri = ChannelUri.parse(ctx.memberStatusChannel());
                ClusterMember.addMemberStatusPublication(
                    eventMember, memberStatusUri, ctx.memberStatusStreamId(), aeron);
            }

            clusterMembers = ClusterMember.addMember(clusterMembers, eventMember);
            clusterMemberByIdMap.put(memberId, eventMember);
            rankedPositions = new long[ClusterMember.quorumThreshold(clusterMembers.length)];
        }
    }

    private void clusterMemberQuit(final int memberId)
    {
        clusterMembers = ClusterMember.removeMember(clusterMembers, memberId);
        clusterMemberByIdMap.remove(memberId);
        rankedPositions = new long[ClusterMember.quorumThreshold(clusterMembers.length)];
    }

    private void closeExistingLog()
    {
        logPublisher.disconnect(ctx.countedErrorHandler());
        logAdapter.disconnect(ctx.countedErrorHandler());
    }

    private void onUnavailableIngressImage(final Image image)
    {
        ingressAdapter.freeSessionBuffer(image.sessionId());
    }

    private void enqueueServiceSessionMessage(
        final MutableDirectBuffer buffer, final int offset, final int length, final long clusterSessionId)
    {
        final int headerOffset = offset - SessionMessageHeaderDecoder.BLOCK_LENGTH;
        final int clusterSessionIdOffset = headerOffset + SessionMessageHeaderDecoder.clusterSessionIdEncodingOffset();
        final int timestampOffset = headerOffset + SessionMessageHeaderDecoder.timestampEncodingOffset();

        buffer.putLong(clusterSessionIdOffset, clusterSessionId, SessionMessageHeaderDecoder.BYTE_ORDER);
        buffer.putLong(timestampOffset, Long.MAX_VALUE, SessionMessageHeaderDecoder.BYTE_ORDER);
        if (!pendingServiceMessages.append(buffer, offset - SESSION_HEADER_LENGTH, length + SESSION_HEADER_LENGTH))
        {
            throw new ClusterException("pending service message buffer capacity: " + pendingServiceMessages.size());
        }
    }

    private boolean serviceSessionMessageAppender(
        final MutableDirectBuffer buffer, final int offset, final int length, final int headOffset)
    {
        final int headerOffset = offset + MessageHeaderDecoder.ENCODED_LENGTH;
        final int clusterSessionIdOffset = headerOffset + SessionMessageHeaderDecoder.clusterSessionIdEncodingOffset();
        final int timestampOffset = headerOffset + SessionMessageHeaderDecoder.timestampEncodingOffset();
        final long clusterSessionId = buffer.getLong(clusterSessionIdOffset, SessionMessageHeaderDecoder.BYTE_ORDER);

        final long appendPosition = logPublisher.appendMessage(
            leadershipTermId,
            clusterSessionId,
            clusterClock.time(),
            buffer,
            offset + SESSION_HEADER_LENGTH,
            length - SESSION_HEADER_LENGTH);

        if (appendPosition > 0)
        {
            ++uncommittedServiceMessages;
            logServiceSessionId = clusterSessionId;
            pendingServiceMessageHeadOffset = headOffset;
            buffer.putLong(timestampOffset, appendPosition, SessionMessageHeaderEncoder.BYTE_ORDER);

            return true;
        }

        return false;
    }

    private boolean serviceSessionMessageReset(
        final MutableDirectBuffer buffer, final int offset, final int length, final int headOffset)
    {
        final int timestampOffset = offset +
            MessageHeaderDecoder.ENCODED_LENGTH + SessionMessageHeaderDecoder.timestampEncodingOffset();
        final long appendPosition = buffer.getLong(timestampOffset, SessionMessageHeaderDecoder.BYTE_ORDER);

        if (appendPosition < Long.MAX_VALUE)
        {
            buffer.putLong(timestampOffset, Long.MAX_VALUE, SessionMessageHeaderEncoder.BYTE_ORDER);
            return true;
        }

        return false;
    }

    private boolean leaderServiceSessionMessageSweeper(
        final MutableDirectBuffer buffer, final int offset, final int length, final int headOffset)
    {
        final int timestampOffset = offset +
            MessageHeaderDecoder.ENCODED_LENGTH + SessionMessageHeaderDecoder.timestampEncodingOffset();
        final long appendPosition = buffer.getLong(timestampOffset, SessionMessageHeaderDecoder.BYTE_ORDER);

        if (appendPosition <= commitPosition.getWeak())
        {
            --uncommittedServiceMessages;
            return true;
        }

        return false;
    }

    private boolean followerServiceSessionMessageSweeper(
        final MutableDirectBuffer buffer, final int offset, final int length, final int headOffset)
    {
        final int clusterSessionIdOffset = offset +
            MessageHeaderDecoder.ENCODED_LENGTH + SessionMessageHeaderDecoder.clusterSessionIdEncodingOffset();

        return buffer.getLong(clusterSessionIdOffset, SessionMessageHeaderDecoder.BYTE_ORDER) <= logServiceSessionId;
    }

    private void onUnavailableCounter(final CountersReader counters, final long registrationId, final int counterId)
    {
        for (final long clientId : serviceClientIds)
        {
            if (registrationId == clientId &&
                ConsensusModule.State.TERMINATING != state &&
                ConsensusModule.State.QUITTING != state)
            {
                ctx.errorHandler().onError(new ClusterException(
                    "Aeron client for service closed unexpectedly", AeronException.Category.WARN));
                state(ConsensusModule.State.CLOSED);
                ctx.terminationHook().run();
            }
        }
    }
}
