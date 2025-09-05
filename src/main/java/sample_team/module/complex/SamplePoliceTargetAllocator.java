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
        int currentTime = agentInfo.getTime();

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
        
        // 检查目标是否仍然存在
        StandardEntity entity = worldInfo.getEntity(target);
        if (!(entity instanceof Road)) return false;
        
        Road road = (Road) entity;
        return road.isBlockadesDefined() && !road.getBlockades().isEmpty();
    }

    private double calculateAssignmentScore(EntityID policeID, Blockade blockade) {
        int distance = worldInfo.getDistance(policeID, blockade.getPosition());
        if (distance < 0) return -1; // 不可达
        
        int cost = blockade.getRepairCost();
        
        // 基础分数 = 成本/距离^0.8
        double score = cost * 100.0 / Math.pow(distance + 1, 0.8);
        
        // 如果之前不可达，降低优先级
        if (isUnreachable(policeID, blockade.getPosition())) {
            score *= 0.4;
        }
        
        return score;
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
        // 从通信消息中获取不可达信息（简化实现）
        // 实际项目中应从消息系统获取
    }

    @Override
    public SamplePoliceTargetAllocator updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        return this;
    }
}