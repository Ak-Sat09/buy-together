package com.buytogether.group;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;

public interface GroupRepository extends MongoRepository<BuyingGroup, String>, GroupRepositoryCustom {

    long countByCreatorIdAndStatusIn(String creatorId, Collection<GroupStatus> statuses);
}
