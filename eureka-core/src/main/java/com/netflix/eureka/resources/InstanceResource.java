/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.eureka.resources;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.cluster.PeerEurekaNode;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A <em>jersey</em> resource that handles operations for a particular instance.
 *
 * @author Karthik Ranganathan, Greg Kim
 *
 */
@Produces({"application/xml", "application/json"})
public class InstanceResource {
    private static final Logger logger = LoggerFactory
            .getLogger(InstanceResource.class);

    private final PeerAwareInstanceRegistry registry;
    private final EurekaServerConfig serverConfig;
    private final String id;
    private final ApplicationResource app;


    InstanceResource(ApplicationResource app, String id, EurekaServerConfig serverConfig, PeerAwareInstanceRegistry registry) {
        this.app = app;
        this.id = id;
        this.serverConfig = serverConfig;
        this.registry = registry;
    }

    /**
     * Get requests returns the information about the instance's
     * {@link InstanceInfo}.
     *
     * @return response containing information about the the instance's
     *         {@link InstanceInfo}.
     */
    @GET
    public Response getInstanceInfo() {
        InstanceInfo appInfo = registry
                .getInstanceByAppAndId(app.getName(), id);
        if (appInfo != null) {
            logger.debug("Found: {} - {}", app.getName(), id);
            return Response.ok(appInfo).build();
        } else {
            logger.debug("Not Found: {} - {}", app.getName(), id);
            return Response.status(Status.NOT_FOUND).build();
        }
    }

    /**
     * A put request for renewing lease from a client instance.
     *
     * @param isReplication
     *            a header parameter containing information whether this is
     *            replicated from other nodes.
     * @param overriddenStatus
     *            overridden status if any.
     * @param status
     *            the {@link InstanceStatus} of the instance.
     * @param lastDirtyTimestamp
     *            last timestamp when this instance information was updated.
     * @return response indicating whether the operation was a success or
     *         failure.
     */
    @PUT  //续约
    public Response renewLease(
            @HeaderParam(PeerEurekaNode.HEADER_REPLICATION) String isReplication,
            @QueryParam("overriddenstatus") String overriddenStatus,
            @QueryParam("status") String status,
            @QueryParam("lastDirtyTimestamp") String lastDirtyTimestamp) {
        //isReplication表示当前请求是接受EurkeaClient端发过来的，还是其他EurkeaServer发送过来同步消息的请求
        //isReplication == true表示是其他EurkeaServer发送过来的同步请求
        boolean isFromReplicaNode = "true".equals(isReplication);
        boolean isSuccess = registry.renew(app.getName(), id, isFromReplicaNode);

        // Not found in the registry, immediately ask for a register
        if (!isSuccess) {
            logger.warn("Not Found (Renew): {} - {}", app.getName(), id);
            return Response.status(Status.NOT_FOUND).build();
        }
        // Check if we need to sync based on dirty time stamp, the client   续约成功之后校验时间是否一致
        // instance might have changed some value
        Response response;
        // 比较eureka-client 传过来的lastDirtyTimestamp  与 eurkeka-Server中的lastDirtyTimestamp
        if (lastDirtyTimestamp != null && serverConfig.shouldSyncWhenTimestampDiffers()) {
            response = this.validateDirtyTimestamp(Long.valueOf(lastDirtyTimestamp), isFromReplicaNode);
            // Store the overridden status since the validation found out the node that replicates wins
            if (response.getStatus() == Response.Status.NOT_FOUND.getStatusCode()
                    && (overriddenStatus != null)
                    && !(InstanceStatus.UNKNOWN.name().equals(overriddenStatus))
                    && isFromReplicaNode) {
                registry.storeOverriddenStatusIfRequired(app.getAppName(), id, InstanceStatus.valueOf(overriddenStatus));
            }
        } else {
            response = Response.ok().build();
        }
        logger.debug("Found (Renew): {} - {}; reply status={}", app.getName(), id, response.getStatus());
        return response;
    }

    /**
     * Handles {@link InstanceStatus} updates.
     *
     * <p>
     * The status updates are normally done for administrative purposes to
     * change the instance status between {@link InstanceStatus#UP} and
     * {@link InstanceStatus#OUT_OF_SERVICE} to select or remove instances for
     * receiving traffic.
     * </p>
     *
     * @param newStatus
     *            the new status of the instance.
     * @param isReplication
     *            a header parameter containing information whether this is
     *            replicated from other nodes.
     * @param lastDirtyTimestamp
     *            last timestamp when this instance information was updated.
     * @return response indicating whether the operation was a success or
     *         failure.
     */
    /**
     * 这里的覆盖状态的作用是将注册中的实例状态改掉，然而EurekaClient 服务提供者的状态并没有变
     * 然后EurkaClient的 消费者，拿到注册信息的是，发现提供者的服务down,从而过滤掉服务提供者。
     * @param newStatus
     * @param isReplication
     * @param lastDirtyTimestamp
     * @return
     */
    @PUT
    @Path("status")
    public Response statusUpdate(
            @QueryParam("value") String newStatus,
            @HeaderParam(PeerEurekaNode.HEADER_REPLICATION) String isReplication,
            @QueryParam("lastDirtyTimestamp") String lastDirtyTimestamp) {
        try {
            //实例不存在
            if (registry.getInstanceByAppAndId(app.getName(), id) == null) {
                logger.warn("Instance not found: {}/{}", app.getName(), id);
                return Response.status(Status.NOT_FOUND).build();
            }
            //覆盖更新状态
            boolean isSuccess = registry.statusUpdate(app.getName(), id,
                    InstanceStatus.valueOf(newStatus), lastDirtyTimestamp,
                    "true".equals(isReplication));

            if (isSuccess) {
                logger.info("Status updated: {} - {} - {}", app.getName(), id, newStatus);
                return Response.ok().build();
            } else {
                logger.warn("Unable to update status: {} - {} - {}", app.getName(), id, newStatus);
                return Response.serverError().build();
            }
        } catch (Throwable e) {
            logger.error("Error updating instance {} for status {}", id,
                    newStatus);
            return Response.serverError().build();
        }
    }

    /**
     * Removes status override for an instance, set with
     * {@link #statusUpdate(String, String, String)}.
     *
     * @param isReplication
     *            a header parameter containing information whether this is
     *            replicated from other nodes.
     * @param lastDirtyTimestamp
     *            last timestamp when this instance information was updated.
     * @return response indicating whether the operation was a success or
     *         failure.
     *  删除实例覆盖状态接口
     */
    @DELETE
    @Path("status")
    public Response deleteStatusUpdate(
            @HeaderParam(PeerEurekaNode.HEADER_REPLICATION) String isReplication,
            @QueryParam("value") String newStatusValue,
            @QueryParam("lastDirtyTimestamp") String lastDirtyTimestamp) {
        try {
            //实例不存在
            if (registry.getInstanceByAppAndId(app.getName(), id) == null) {
                logger.warn("Instance not found: {}/{}", app.getName(), id);
                return Response.status(Status.NOT_FOUND).build();
            }

            InstanceStatus newStatus = newStatusValue == null ? InstanceStatus.UNKNOWN : InstanceStatus.valueOf(newStatusValue);
            boolean isSuccess = registry.deleteStatusOverride(app.getName(), id,
                    newStatus, lastDirtyTimestamp, "true".equals(isReplication));

            if (isSuccess) {
                logger.info("Status override removed: {} - {}", app.getName(), id);
                return Response.ok().build();
            } else {
                logger.warn("Unable to remove status override: {} - {}", app.getName(), id);
                return Response.serverError().build();
            }
        } catch (Throwable e) {
            logger.error("Error removing instance's {} status override", id);
            return Response.serverError().build();
        }
    }

    /**
     * Updates user-specific metadata information. If the key is already available, its value will be overwritten.
     * If not, it will be added.
     * @param uriInfo - URI information generated by jersey.
     * @return response indicating whether the operation was a success or
     *         failure.
     */
    @PUT
    @Path("metadata")
    public Response updateMetadata(@Context UriInfo uriInfo) {
        try {
            InstanceInfo instanceInfo = registry.getInstanceByAppAndId(app.getName(), id);
            // ReplicationInstance information is not found, generate an error
            if (instanceInfo == null) {
                logger.warn("Cannot find instance while updating metadata for instance {}/{}", app.getName(), id);
                return Response.status(Status.NOT_FOUND).build();
            }
            MultivaluedMap<String, String> queryParams = uriInfo.getQueryParameters();
            Set<Entry<String, List<String>>> entrySet = queryParams.entrySet();
            Map<String, String> metadataMap = instanceInfo.getMetadata();
            // Metadata map is empty - create a new map
            if (Collections.emptyMap().getClass().equals(metadataMap.getClass())) {
                metadataMap = new ConcurrentHashMap<>();
                InstanceInfo.Builder builder = new InstanceInfo.Builder(instanceInfo);
                builder.setMetadata(metadataMap);
                instanceInfo = builder.build();
            }
            // Add all the user supplied entries to the map
            for (Entry<String, List<String>> entry : entrySet) {
                metadataMap.put(entry.getKey(), entry.getValue().get(0));
            }
            registry.register(instanceInfo, false);
            return Response.ok().build();
        } catch (Throwable e) {
            logger.error("Error updating metadata for instance {}", id, e);
            return Response.serverError().build();
        }

    }

    /**
     * Handles cancellation of leases for this particular instance.
     *
     * @param isReplication
     *            a header parameter containing information whether this is
     *            replicated from other nodes.
     * @return response indicating whether the operation was a success or
     *         failure.
     *       下线
     */
    @DELETE
    public Response cancelLease(
            @HeaderParam(PeerEurekaNode.HEADER_REPLICATION) String isReplication) {
        try {
            boolean isSuccess = registry.cancel(app.getName(), id,
                "true".equals(isReplication));

            if (isSuccess) {
                logger.debug("Found (Cancel): {} - {}", app.getName(), id);
                return Response.ok().build();
            } else {
                logger.info("Not Found (Cancel): {} - {}", app.getName(), id);
                return Response.status(Status.NOT_FOUND).build();
            }
        } catch (Throwable e) {
            logger.error("Error (cancel): {} - {}", app.getName(), id, e);
            return Response.serverError().build();
        }

    }

    private Response validateDirtyTimestamp(Long lastDirtyTimestamp,
                                            boolean isReplication) {
        // eureke-server中的 实例
        InstanceInfo appInfo = registry.getInstanceByAppAndId(app.getName(), id, false);
        if (appInfo != null) {
            if ((lastDirtyTimestamp != null) && (!lastDirtyTimestamp.equals(appInfo.getLastDirtyTimestamp()))) {
                Object[] args = {id, appInfo.getLastDirtyTimestamp(), lastDirtyTimestamp, isReplication};

                //eureka-client请求过来的修改时间大于 注册中心的修改时间  则表明client实例与注册中心的实例数据不一致 这返回404
                if (lastDirtyTimestamp > appInfo.getLastDirtyTimestamp()) {
                    logger.debug(
                            "Time to sync, since the last dirty timestamp differs -"
                                    + " ReplicationInstance id : {},Registry : {} Incoming: {} Replication: {}",
                            args);
                    return Response.status(Status.NOT_FOUND).build(); //返回404又重新触发注册，同步注册中心与Client的数据
                } else if (appInfo.getLastDirtyTimestamp() > lastDirtyTimestamp) {
                    // In the case of replication, send the current instance info in the registry for the
                    // replicating node to sync itself with this one.
                    if (isReplication) {
                        logger.debug(
                                "Time to sync, since the last dirty timestamp differs -"
                                        + " ReplicationInstance id : {},Registry : {} Incoming: {} Replication: {}",
                                args);
                        return Response.status(Status.CONFLICT).entity(appInfo).build();
                    } else {
                        return Response.ok().build();
                    }
                }
            }

        }
        return Response.ok().build();
    }
}
