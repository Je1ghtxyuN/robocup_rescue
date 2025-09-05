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
    
    public SamplePoliceTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
        ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
    }

    @Override
    public PoliceTargetAllocator resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        return this;
    }

    @Override
    public PoliceTargetAllocator preparate() {
        super.preparate();
        return this;
    }

    @Override
    public Map<EntityID, EntityID> getResult() {
        Map<EntityID, EntityID> allocation = new HashMap<>();

        // 获取所有警察和所有有效障碍物
        Collection<StandardEntity> policeForces = worldInfo.getEntitiesOfType(StandardEntityURN.POLICE_FORCE);
        List<Blockade> validBlockades = getValidBlockades();
        
        // 按修复成本和位置分组
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
            
            // 检查是否有上一周期的分配
            if (previousAllocation.containsKey(policeID)) {
                EntityID previousTarget = previousAllocation.get(policeID);
                
                // 如果上一目标仍然有效，优先考虑
                for (Blockade blockade : validBlockades) {
                    if (blockade.getPosition().equals(previousTarget)) {
                        // 计算分配分数
                        double score = calculateAssignmentScore(policeID, blockade);
                        
                        if (score > bestScore) {
                            bestScore = score;
                            bestBlockade = blockade;
                        }
                    }
                }
            }
            
            // 从高优先级组开始分配
            for (List<Blockade> group : priorityGroups.values()) {
                for (Blockade blockade : group) {
                    // 跳过已分配目标
                    if (allocatedTargets.contains(blockade.getPosition())) {
                        continue;
                    }
                    
                    // 计算分配分数
                    double score = calculateAssignmentScore(policeID, blockade);
                    
                    // 如果目标已分配给其他警察，降低优先级
                    if (allocatedTargets.contains(blockade.getPosition())) {
                        score *= 0.6;
                    }
                    
                    if (score > bestScore) {
                        bestScore = score;
                        bestBlockade = blockade;
                    }
                }
                
                // 找到本组最佳目标
                if (bestBlockade != null) {
                    allocation.put(policeID, bestBlockade.getPosition());
                    allocatedTargets.add(bestBlockade.getPosition());
                    break;
                }
            }
        }
        
        // 保存当前分配供下一周期参考
        previousAllocation = new HashMap<>(allocation);

        return allocation;
    }

    private double calculateAssignmentScore(EntityID policeID, Blockade blockade) {
        int distance = worldInfo.getDistance(policeID, blockade.getPosition());
        int cost = blockade.getRepairCost();
        
        // 基础分数 = 成本/距离^0.7（减弱距离影响）
        double score = cost * 100.0 / Math.pow(distance + 1, 0.7);
        
        // 如果之前不可达，降低优先级
        if (isUnreachable(policeID, blockade.getPosition())) {
            score *= 0.3;
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
            if (blockade.isRepairCostDefined() && 
                blockade.getRepairCost() > 0 &&
                blockade.getPosition() != null) {
                
                blockades.add(blockade);
            }
        }
        
        // 按修复成本降序排序
        blockades.sort((b1, b2) -> Integer.compare(b2.getRepairCost(), b1.getRepairCost()));
        return blockades;
    }

    @Override
    public PoliceTargetAllocator calc() {
        // 更新不可达地图
        updateUnreachableMap();
        return this;
    }
    
    private void updateUnreachableMap() {
        // 从消息中获取不可达信息
        // （实际实现需要与PoliceSearch协调）
        // 这里简化处理，实际需要从消息系统获取
    }

    @Override
    public PoliceTargetAllocator updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        return this;
    }
}