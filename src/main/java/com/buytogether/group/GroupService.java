package com.buytogether.group;

import com.buytogether.common.error.BusinessException;
import com.buytogether.common.error.ErrorCode;
import com.buytogether.common.text.Text;
import com.buytogether.group.cache.GroupCache;
import com.buytogether.group.member.GroupMember;
import com.buytogether.group.member.GroupMemberRepository;
import com.buytogether.group.member.MemberRole;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Everything that changes a group. Reads live in {@link GroupQueryService}.
 *
 * MongoDB has no cross-document transaction here, so membership and the member
 * counter are kept consistent with a unique index plus a compensating delete
 * when a seat could not be taken.
 */
@Service
@RequiredArgsConstructor
public class GroupService {

    private final GroupRepository groups;
    private final GroupMemberRepository members;
    private final GroupProperties properties;
    private final GroupCache cache;
    private final Clock clock;

    public GroupView create(String creatorId, CreateGroupRequest request) {
        validateSize(request.targetSize());
        validateDeadline(request.deadline());
        if (groups.countByCreatorIdAndStatusIn(creatorId, GroupStatus.ACTIVE) >= properties.maxOpenPerUser()) {
            throw new BusinessException(ErrorCode.TOO_MANY_ACTIVE_GROUPS);
        }

        Instant now = clock.instant();
        BuyingGroup group = groups.save(BuyingGroup.builder()
                .brand(Text.clean(request.brand()))
                .model(Text.clean(request.model()))
                .city(Text.clean(request.city()))
                .brandKey(Text.key(request.brand()))
                .modelKey(Text.key(request.model()))
                .cityKey(Text.key(request.city()))
                .note(Text.clean(request.note()))
                .targetSize(request.targetSize())
                .memberCount(1)
                .status(GroupStatus.OPEN)
                .creatorId(creatorId)
                .deadline(request.deadline())
                .createdAt(now)
                .updatedAt(now)
                .build());

        try {
            members.insert(membership(group.getId(), creatorId, MemberRole.CREATOR, now));
        } catch (RuntimeException e) {
            groups.deleteById(group.getId());
            throw e;
        }

        cache.evict(group.getId());
        return GroupView.from(group);
    }

    public GroupView join(String groupId, String userId) {
        BuyingGroup group = load(groupId);
        ensureJoinable(group);

        Instant now = clock.instant();
        try {
            members.insert(membership(groupId, userId, MemberRole.MEMBER, now));
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.ALREADY_MEMBER);
        }

        Optional<BuyingGroup> seat = groups.takeSeat(groupId, group.getTargetSize(), now);
        if (seat.isEmpty()) {
            // Someone else took the last seat between our read and our update
            members.deleteByGroupIdAndUserId(groupId, userId);
            throw new BusinessException(ErrorCode.GROUP_FULL);
        }
        if (seat.get().spotsLeft() == 0) {
            groups.markFullIfTargetReached(groupId, group.getTargetSize(), now);
        }

        cache.evict(groupId);
        return GroupView.from(load(groupId));
    }

    public GroupView leave(String groupId, String userId) {
        BuyingGroup group = load(groupId);
        if (group.isCreatedBy(userId)) {
            throw new BusinessException(ErrorCode.CREATOR_CANNOT_LEAVE);
        }
        if (!group.getStatus().isActive()) {
            throw new BusinessException(ErrorCode.GROUP_INACTIVE);
        }
        if (members.deleteByGroupIdAndUserId(groupId, userId) == 0) {
            throw new BusinessException(ErrorCode.NOT_A_MEMBER);
        }

        groups.releaseSeat(groupId, clock.instant());
        cache.evict(groupId);
        return GroupView.from(load(groupId));
    }

    public GroupView close(String groupId, String userId) {
        BuyingGroup group = load(groupId);
        if (!group.isCreatedBy(userId)) {
            throw new BusinessException(ErrorCode.NOT_GROUP_CREATOR);
        }
        if (!groups.close(groupId, clock.instant())) {
            throw new BusinessException(ErrorCode.GROUP_INACTIVE);
        }

        cache.evict(groupId);
        return GroupView.from(load(groupId));
    }

    public long expireOverdueGroups() {
        long expired = groups.expireDeadlinesBefore(LocalDate.now(clock), clock.instant());
        if (expired > 0) {
            cache.evictAll();
        }
        return expired;
    }

    private BuyingGroup load(String groupId) {
        return groups.findById(groupId).orElseThrow(() -> new BusinessException(ErrorCode.GROUP_NOT_FOUND));
    }

    private static void ensureJoinable(BuyingGroup group) {
        if (group.getStatus() == GroupStatus.FULL) {
            throw new BusinessException(ErrorCode.GROUP_FULL);
        }
        if (group.getStatus() != GroupStatus.OPEN) {
            throw new BusinessException(ErrorCode.GROUP_NOT_JOINABLE);
        }
    }

    private void validateSize(int targetSize) {
        if (targetSize < properties.minSize() || targetSize > properties.maxSize()) {
            throw new BusinessException(ErrorCode.INVALID_GROUP_SIZE,
                    "Group size must be between " + properties.minSize() + " and " + properties.maxSize());
        }
    }

    private void validateDeadline(LocalDate deadline) {
        if (deadline == null) {
            return;
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate latest = today.plusDays(properties.maxDeadlineDays());
        if (deadline.isBefore(today) || deadline.isAfter(latest)) {
            throw new BusinessException(ErrorCode.INVALID_DEADLINE,
                    "Deadline must be between today and " + latest);
        }
    }

    private static GroupMember membership(String groupId, String userId, MemberRole role, Instant joinedAt) {
        return GroupMember.builder()
                .groupId(groupId)
                .userId(userId)
                .role(role)
                .joinedAt(joinedAt)
                .build();
    }
}
