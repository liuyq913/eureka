package com.netflix.eureka2.server.service.overrides;

import com.netflix.eureka2.interests.ChangeNotification;
import com.netflix.eureka2.registry.instance.Delta;
import rx.Observable;

import java.util.Set;

/**
 * A registry that holds override data that can be applied to instanceInfos.
 *
 * @author David Liu
 */
public interface OverridesRegistry {

    /**
     * Get all overrides for a given id
     *
     * @param id
     * @return
     */
    Set<Delta<?>> get(String id);


    /**
     * Set all overrides for the given id. If there are any previous overrides, they are discarded.
     *
     * Implementations will need to deal with concurrency between set and remove, if any.
     *
     * @param id
     * @param deltas
     * @return
     */
    Observable<Void> set(String id, Set<Delta<?>> deltas);


    /**
     * Remove all current overrides for the given id.
     *
     * Implementations will need to deal with concurrency between set and remove, if any.
     *
     * @param id
     * @return
     */
    Observable<Void> remove(String id);

    /**
     *
     * @return a stream of updates on additions and removals for overrides
     */
    Observable<ChangeNotification<Overrides>> forUpdates();


}