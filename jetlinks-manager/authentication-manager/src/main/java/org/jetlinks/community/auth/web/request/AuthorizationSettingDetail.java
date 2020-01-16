package org.jetlinks.community.auth.web.request;

import lombok.*;
import org.apache.commons.collections.CollectionUtils;
import org.hswebframework.web.authorization.Dimension;
import org.hswebframework.web.authorization.access.DataAccessConfig;
import org.hswebframework.web.system.authorization.api.entity.AuthorizationSettingEntity;
import org.hswebframework.web.system.authorization.api.entity.DataAccessEntity;

import javax.validation.constraints.NotBlank;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 权限设置详情信息
 *
 * @author zhouhao
 * @since 1.0
 */
@Getter
@Setter
public class AuthorizationSettingDetail {

    /**
     * 设置目标类型(维度)标识,如: org, role
     */
    @NotBlank
    private String targetType;

    /**
     * 设置目标.
     */
    @NotBlank
    private String targetId;

    /**
     * 冲突时是否合并
     */
    private boolean merge = true;

    /**
     * 冲突时优先级
     */
    private int priority = 10;

    /**
     * 权限列表
     */
    private List<PermissionInfo> permissionList;

    /**
     * 授权信息
     */
    @Getter
    @Setter
    @EqualsAndHashCode(of = "id")
    public static class PermissionInfo {

        /**
         * 权限ID
         */
        @NotBlank
        private String id;

        /**
         * 授权操作
         */
        private Set<String> actions;

        /**
         * 字段权限
         */
        private List<FiledAccess> filedAccess;

        /**
         * 数据权限
         */
        private List<DataAccess> dataAccess;

        private PermissionInfo unwrap(AuthorizationSettingEntity entity) {
            this.id = entity.getPermission();
            this.actions = entity.getActions();
            this.filedAccess = new ArrayList<>();
            this.dataAccess = new ArrayList<>();

            //filed : access
            Map<String, FiledAccess> filedAccessMap = new LinkedHashMap<>();


            //type : access
            Map<String, DataAccess> dataAccessMap = new LinkedHashMap<>();
            if (CollectionUtils.isNotEmpty(entity.getDataAccesses())) {
                for (DataAccessEntity access : entity.getDataAccesses()) {
                    //字段权限
                    if (DataAccessConfig.DefaultType.DENY_FIELDS.equalsIgnoreCase(access.getType())) {
                        Set<String> fields = Optional.ofNullable(access.getConfig())
                            .<Set<String>>map(conf -> new HashSet<>((Collection<String>) conf.get("fields")))
                            .orElseGet(HashSet::new);

                        for (String field : fields) {
                            filedAccessMap
                                .computeIfAbsent(field, filedName -> new FiledAccess(filedName, new HashSet<>()))
                                .getAction().add(access.getAction());
                        }
                    } else {
                        //维度权限
                        if (DataAccessConfig.DefaultType.DIMENSION_SCOPE.equalsIgnoreCase(access.getType())) {
                            DataAccess dataAccess = DataAccess.of(access);

                            dataAccessMap.computeIfAbsent(dataAccess.getType(), __ -> dataAccess)
                                .actions
                                .add(access.getAction());

                        }
                    }
                }
            }
            this.dataAccess.addAll(dataAccessMap.values());
            this.filedAccess.addAll(filedAccessMap.values());

            return this;
        }

        private void wrap(AuthorizationSettingEntity entity) {
            entity.setPermission(id);
            entity.setActions(actions);
            List<DataAccessEntity> entities = new ArrayList<>();
            if (CollectionUtils.isNotEmpty(filedAccess)) {
                Map<String, Set<String>> group = new HashMap<>();
                for (FiledAccess access : filedAccess) {
                    for (String action : access.getAction()) {
                        group.computeIfAbsent(action, r -> new HashSet<>())
                            .add(access.name);
                    }
                }
                for (Map.Entry<String, Set<String>> entry : group.entrySet()) {
                    DataAccessEntity dataAccessEntity = new DataAccessEntity();
                    dataAccessEntity.setAction(entry.getKey());
                    dataAccessEntity.setType(DataAccessConfig.DefaultType.DENY_FIELDS);
                    dataAccessEntity.setConfig(Collections.singletonMap("fields", entry.getValue()));
                    entities.add(dataAccessEntity);
                }
            }

            if (CollectionUtils.isNotEmpty(dataAccess)) {
                for (DataAccess access : dataAccess) {
                    entities.addAll(access.toEntity());
                }
            }
            entity.setDataAccesses(entities);
        }

    }


    /**
     * 数据权限
     */
    @Getter
    @Setter
    public static class DataAccess {

        /**
         * 维度类型,如: org
         */
        private String type;

        /**
         * 操作
         */
        private Set<String> actions;

        /**
         * 其他配置
         */
        private Map<String, Object> config;

        public static DataAccess of(DataAccessEntity entity) {
            DataAccess access = new DataAccess();
            access.config = entity.getConfig();
            access.actions = new HashSet<>();
            access.type = (String) access.getConfig().get("scopeType");
            return access;
        }

        public List<DataAccessEntity> toEntity() {
            if(CollectionUtils.isEmpty(actions)){
                return Collections.emptyList();
            }
            return actions
                .stream()
                .map(action -> {
                    DataAccessEntity entity = new DataAccessEntity();
                    entity.setType(DataAccessConfig.DefaultType.DIMENSION_SCOPE);
                    Map<String, Object> config = new HashMap<>();
                    if (null != this.config) {
                        config.putAll(this.config);
                    }
                    config.put("scopeType", type);
                    config.put("children", true);
                    entity.setConfig(config);
                    entity.setAction(action);
                    return entity;
                }).collect(Collectors.toList());
        }
    }

    /**
     * 字段权限,控制指定操作不能访问指定的字段.
     */
    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FiledAccess {

        /**
         * 字段名
         */
        private String name;

        /**
         * 操作
         */
        private Set<String> action;

    }

    public static AuthorizationSettingDetail fromEntity(List<AuthorizationSettingEntity> entities) {
        AuthorizationSettingDetail detail = new AuthorizationSettingDetail();
        detail.setPermissionList(new ArrayList<>());

        for (AuthorizationSettingEntity entity : entities) {
            detail.setTargetId(entity.getDimensionTarget());
            detail.setTargetType(entity.getDimensionType());
            detail.getPermissionList().add(new PermissionInfo().unwrap(entity));
        }

        return detail;
    }

    public List<AuthorizationSettingEntity> toEntity() {
        return toEntity(null);
    }

    public List<AuthorizationSettingEntity> toEntity(Dimension dimension) {
        if (CollectionUtils.isEmpty(permissionList)) {
            return Collections.emptyList();
        }
        return permissionList
            .stream()
            .filter(permissionInfo -> CollectionUtils.isNotEmpty(permissionInfo.actions))
            .map(permissionInfo -> {
                AuthorizationSettingEntity entity = new AuthorizationSettingEntity();
                entity.setDimensionTarget(targetId);
                entity.setDimensionType(targetType);
                if (null != dimension) {
                    entity.setDimensionTypeName(dimension.getType().getName());
                    entity.setDimensionTargetName(dimension.getName());
                }
                entity.setState((byte) 1);
                entity.setMerge(merge);
                entity.setPriority(priority);
                permissionInfo.wrap(entity);
                return entity;
            }).collect(Collectors.toList());
    }
}