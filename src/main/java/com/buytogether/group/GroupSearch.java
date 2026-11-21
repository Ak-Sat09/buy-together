package com.buytogether.group;

import com.buytogether.common.text.Text;

/**
 * Browse filters, already normalised. Being a record it is also a safe cache key.
 */
public record GroupSearch(String cityKey, String brandKey, String modelKey, GroupStatus status) {

    public static GroupSearch of(String city, String brand, String model, GroupStatus status) {
        return new GroupSearch(Text.key(city), Text.key(brand), Text.key(model),
                status == null ? GroupStatus.OPEN : status);
    }
}
