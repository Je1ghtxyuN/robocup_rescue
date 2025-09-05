package sample_team.module.complex;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.complex.PoliceTargetAllocator;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;
import java.util.*;

public class SamplePoliceTargetAllocator extends PoliceTargetAllocator {

    private Map<EntityID, Set<EntityID>> unreachableMap = new HashMap<>();
    private Map<EntityID, EntityID> previousAllocation = new HashMap<>();
    private Map<EntityID, Integer> allocationTime = new HashMap<>();
    private int currentTime;
    
    // 统一人类紧急因子参数
    private static final int BASE_HP = 10000;
    
    public SamplePoliceTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
        ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
    }

    @Override
    public SamplePoliceTargetAllocator resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        return this;
    }

    @Override
    public SamplePoliceTargetAllocator preparate() {
        super.preparate();
        return this;
    }

    @Override
    public Map<EntityID, EntityID> getResult() {
        Map<EntityID, EntityID> allocation = new HashMap<>();
        currentTime = agentInfo.getTime();

        // 获取所有警察和有效障碍物
        Collection<StandardEntity> policeForces = worldInfo.getEntitiesOfType(StandardEntityURN.POLICE_FORCE);
        List<Blockade> validBlockades = getValidBlockades();
        
        // 按修复成本分组（降序）
        Map<Integer, List<Blockade>> priorityGroups = new TreeMap<>(Collections.reverseOrder());
        for (Blockade blockade : validBlockades) {
            int cost = blockade.getRepairCost();
            priorityGroups.computeIfAbsent(cost, k -> new ArrayList<>()).add(blockade);
        }
        
        // 记录已分配目标
        Set<EntityID> allocatedTargets = new HashSet<>();
        
        // 为每个警察分配目标
        for (StandardEntity police : policeForces) {
            EntityID policeID = police.getID();
            Blockade bestBlockade = null;
            double bestScore = Double.MIN_VALUE;
            
            // 检查是否有有效的上次分配
            EntityID previousTarget = previousAllocation.get(policeID);
            if (previousTarget != null && isTargetStillValid(previousTarget, currentTime)) {
                Blockade previousBlockade = findBlockadeByPosition(validBlockades, previousTarget);
                if (previousBlockade != null) {
                    double score = calculateAssignmentScore(policeID, previousBlockade);
                    if (score > 0) {
                        allocation.put(policeID, previousTarget);
                        allocatedTargets.add(previousTarget);
                        allocationTime.put(policeID, currentTime);
                        continue;
                    }
                }
            }
            
            // 从高优先级组开始分配
            for (List<Blockade> group : priorityGroups.values()) {
                for (Blockade blockade : group) {
                    EntityID targetPos = blockade.getPosition();
                    
                    if (allocatedTargets.contains(targetPos)) continue;
                    if (isUnreachable(policeID, targetPos)) continue;
                    
                    double score = calculateAssignmentScore(policeID, blockade);
                    
                    // 新增：提高消防员和救护车聚集区域的优先级
                    double rescueFactor = calculateRescueTeamDensityFactor(targetPos);
                    score *= (1.0 + rescueFactor);
                    
                    if (score > bestScore) {
                        bestScore = score;
                        bestBlockade = blockade;
                    }
                }
                
                if (bestBlockade != null) {
                    EntityID targetPos = bestBlockade.getPosition();
                    allocation.put(policeID, targetPos);
                    allocatedTargets.add(targetPos);
                    allocationTime.put(policeID, currentTime);
                    break;
                }
            }
        }
        
        // 保存当前分配
        previousAllocation = new HashMap<>(allocation);
        return allocation;
    }

    // 新增方法：计算消防员和救护车聚集区域的密度因子
    private double calculateRescueTeamDensityFactor(EntityID position) {
        double densityFactor = 0.0;
        int radius = 50000; // 50米范围内
        
        // 获取位置附近的实体
        Collection<StandardEntity> nearbyEntities = worldInfo.getObjectsInRange(position, radius);
        
        // 统计附近的消防员和救护车数量
        int fireBrigadeCount = 0;
        int ambulanceTeamCount = 0;
        
        for (StandardEntity entity : nearbyEntities) {
            if (entity instanceof FireBrigade) {
                fireBrigadeCount++;
            } else if (entity instanceof AmbulanceTeam) {
                ambulanceTeamCount++;
            }
        }
        
        // 计算密度因子：每增加1个救援队伍，增加0.2的权重
        densityFactor = (fireBrigadeCount + ambulanceTeamCount) * 0.2;
        
        // 如果有3个以上的救援队伍，额外增加权重
        if (fireBrigadeCount + ambulanceTeamCount >= 3) {
            densityFactor *= 1.5;
        }
        
        return densityFactor;
    }

    private Blockade findBlockadeByPosition(List<Blockade> blockades, EntityID position) {
        for (Blockade b : blockades) {
            if (b.getPosition().equals(position)) {
                return b;
            }
        }
        return null;
    }

    private boolean isTargetStillValid(EntityID target, int currentTime) {
        // 分配时间在最近30秒内
        if (currentTime - allocationTime.getOrDefault(target, -1000) > 30) {
            return false;
        }
        
        // 实时检查目标是否仍然存在障碍
        return isBlockadeActiveAt(target);
    }
    
    // 新增方法：实时检查目标位置是否有有效障碍
    private boolean isBlockadeActiveAt(EntityID position) {
        StandardEntity entity = worldInfo.getEntity(position);
        if (!(entity instanceof Road)) return false;
        
        Road road = (Road) entity;
        if (!road.isBlockadesDefined() || road.getBlockades().isEmpty()) {
            return false;
        }
        
        // 检查至少有一个有效障碍
        for (EntityID blockadeID : road.getBlockades()) {
            StandardEntity blockadeEntity = worldInfo.getEntity(blockadeID);
            if (blockadeEntity instanceof Blockade) {
                Blockade blockade = (Blockade) blockadeEntity;
                if (blockade.isRepairCostDefined() && blockade.getRepairCost() > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private double calculateAssignmentScore(EntityID policeID, Blockade blockade) {
        EntityID target = blockade.getPosition();
        int distance = worldInfo.getDistance(policeID, target);
        if (distance < 0) return -1; // 不可达
        
        int cost = blockade.getRepairCost();
        
        // 基础分数 = 成本/距离^0.8
        double score = cost * 100.0 / Math.pow(distance + 1, 0.8);
        
        // 如果之前不可达，降低优先级
        if (isUnreachable(policeID, target)) {
            score *= 0.4;
        }
        
        // 人类紧急程度因子（统一算法）
        double humanEmergencyFactor = calculateHumanEmergencyFactor(target);
        
        return score * humanEmergencyFactor;
    }
    
    // 统一人类紧急因子计算方法
    private double calculateHumanEmergencyFactor(EntityID position) {
        double maxEmergency = 1.0; // 默认值
        
        // 获取该位置的所有人类（平民）
        Collection<StandardEntity> humans = worldInfo.getEntitiesOfType(StandardEntityURN.CIVILIAN);
        for (StandardEntity entity : humans) {
            Human human = (Human) entity;
            if (position.equals(human.getPosition())) {
                // 计算人类紧急程度：HP越低紧急度越高，被埋压程度越高紧急度越高
                int hp = human.isHPDefined() ? human.getHP() : BASE_HP;
                int buriedness = human.isBuriednessDefined() ? human.getBuriedness() : 0;
                
                // 紧急程度 = (10000 - HP) * 0.8 + buriedness * 15
                double emergency = (BASE_HP - hp) * 0.8 + (buriedness * 15);
                if (emergency > maxEmergency) {
                    maxEmergency = emergency;
                }
            }
        }
        
        // 紧急程度因子 = 1.0 + maxEmergency/5000.0
        return 1.0 + (maxEmergency / 5000.0);
    }
    
    private boolean isUnreachable(EntityID policeID, EntityID target) {
        Set<EntityID> unreachable = unreachableMap.getOrDefault(policeID, Collections.emptySet());
        return unreachable.contains(target);
    }

    private List<Blockade> getValidBlockades() {
        List<Blockade> blockades = new ArrayList<>();
        for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.BLOCKADE)) {
            Blockade blockade = (Blockade) entity;
            
            // 有效性检查
            if (!blockade.isRepairCostDefined() || blockade.getRepairCost() <= 0) continue;
            if (blockade.getPosition() == null) continue;
            if (!isBlockadeActive(blockade)) continue;
                
            blockades.add(blockade);
        }
        
        // 按修复成本降序排序
        blockades.sort((b1, b2) -> Integer.compare(b2.getRepairCost(), b1.getRepairCost()));
        return blockades;
    }
    
    private boolean isBlockadeActive(Blockade blockade) {
        EntityID position = blockade.getPosition();
        StandardEntity entity = worldInfo.getEntity(position);
        if (!(entity instanceof Road)) return false;
        
        Road road = (Road) entity;
        return road.isBlockadesDefined() && road.getBlockades().contains(blockade.getID());
    }

    @Override
    public SamplePoliceTargetAllocator calc() {
        updateUnreachableMap();
        return this;
    }
    
    private void updateUnreachableMap() {
        // 从通信消息中获取不可达信息
        // 实际项目中应从消息系统获取
        // 这里简化处理：清空历史不可达记录
        unreachableMap.clear();
    }

    @Override
    public SamplePoliceTargetAllocator updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        return this;
    }
}