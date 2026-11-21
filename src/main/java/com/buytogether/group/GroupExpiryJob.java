package com.buytogether.group;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
class GroupExpiryJob {

    private final GroupService groupService;

    @Scheduled(cron = "${app.groups.expiry-cron}", zone = "Asia/Kolkata")
    void expireOverdueGroups() {
        long expired = groupService.expireOverdueGroups();
        if (expired > 0) {
            log.info("Marked {} groups as expired", expired);
        }
    }
}
