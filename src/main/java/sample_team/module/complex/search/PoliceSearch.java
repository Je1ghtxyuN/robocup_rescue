package sample_team.module.complex;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.module.complex.Search;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;
import java.util.*;

public class PoliceSearch extends Search {

    private PathPlanning pathPlanning;
    private EntityID result;
    private boolean initialized = false;

    public PoliceSearch(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                       ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
    }

    private void initialize(ModuleManager moduleManager) {
        if (!initialized) {
            // 警察专用路径规划（A*算法）
            this.pathPlanning = moduleManager.getModule(
                "PoliceSearch.PathPlanning",
                "sample_team.module.algorithm.PoliceAStarPathPlanning");
            this.initialized = true;
        }
    }

    @Override
    public PoliceSearch updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (pathPlanning != null) {
            pathPlanning.updateInfo(messageManager);
        }
        return this;
    }

    @Override
    public PoliceSearch calc() {
        if (!initialized) {
            initialize(moduleManager);
        }
        
        result = null;
        EntityID myPosition = agentInfo.getPosition();
        
        // 获取所有未清理的障碍物
        List<Blockade> blockades = getUnclearedBlockades();
        
        if (!blockades.isEmpty()) {
            // 按距离排序（最近的优先）
            blockades.sort((b1, b2) -> {
                int d1 = worldInfo.getDistance(myPosition, b1.getID());
                int d2 = worldInfo.getDistance(myPosition, b2.getID());
                return Integer.compare(d1, d2);
            });

            // 选择最近的障碍物
            Blockade targetBlockade = blockades.get(0);
            EntityID roadID = targetBlockade.getPosition();
            
            if (roadID != null) {
                // 设置路径规划
                pathPlanning.setFrom(myPosition);
                pathPlanning.setDestination(Collections.singleton(roadID));
                pathPlanning.calc();
                
                // 设置目标为障碍物所在的道路
                result = roadID;
                return this;
            }
        }
        
        // 没有障碍物时巡逻随机道路
        patrolRandomRoad();
        return this;
    }

    private List<Blockade> getUnclearedBlockades() {
        List<Blockade> blockades = new ArrayList<>();
        for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.BLOCKADE)) {
            Blockade blockade = (Blockade) entity;
            // 只处理未完全损坏的障碍物
            if (blockade.isRepairCostDefined() && blockade.getRepairCost() > 0) {
                blockades.add(blockade);
            }
        }
        return blockades;
    }

    private void patrolRandomRoad() {
        Collection<StandardEntity> roads = worldInfo.getEntitiesOfType(StandardEntityURN.ROAD);
        if (!roads.isEmpty()) {
            List<EntityID> roadIDs = new ArrayList<>();
            for (StandardEntity road : roads) {
                roadIDs.add(road.getID());
            }
            // 随机选择道路
            result = roadIDs.get(new Random().nextInt(roadIDs.size()));
        }
    }

    @Override
    public EntityID getTarget() {
        return result;
    }
}