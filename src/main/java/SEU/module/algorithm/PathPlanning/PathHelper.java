package SEU.module.algorithm.PathPlanning;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.communication.standard.bundle.information.MessageRoad;

import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.component.communication.CommunicationMessage;
import rescuecore2.misc.Pair;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.PoliceForce;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.worldmodel.EntityID;
import adf.core.debug.DefaultLogger;
import org.apache.log4j.Logger;
import rescuecore2.standard.entities.*;
import static rescuecore2.standard.entities.StandardEntityURN.BLOCKADE;
import static rescuecore2.standard.entities.StandardEntityURN.BUILDING;

import java.util.*;

public class PathHelper {
    private AgentInfo agentInfo;
    private WorldInfo worldInfo;
    private Logger logger;
    private Set<EntityID> allKnownRoad;
    private Set<EntityID> allKnownBlockade;
    private Map<Integer, Pair<Integer, Integer>> locationHistory;
    private Map<Integer, EntityID> positionHistory;
    public Map<EntityID, EntityID> unpassable;
    private Map<Integer, Integer> stayTime;
    private Set<EntityID> MybadExit;
    private Set<EntityID> allbadEntrance;
    private final int MinMoveDis = 1500;


    public PathHelper(AgentInfo ai, WorldInfo wi){
        this.agentInfo = ai;
        this.worldInfo = wi;
        this.locationHistory = new TreeMap<Integer, Pair<Integer, Integer>>();
        this.positionHistory = new TreeMap<Integer, EntityID>();
        this.unpassable = new HashMap<>();
        this.stayTime = new HashMap<>();
        this.allKnownRoad = new HashSet<>();
        this.MybadExit = new HashSet<>();
        this.allbadEntrance = new HashSet<>();
        this.allKnownBlockade = new HashSet<>();
        logger = DefaultLogger.getLogger(agentInfo.me());
        this.allKnownBlockade.addAll(this.worldInfo.getEntityIDsOfType(BLOCKADE));
    }

    public void updateInfo(MessageManager messageManager){
        updateLocationHistory();
        undatePositionHistory();
        updateStayTime();
        updateMybadExit();
        updateAllBadEntrance();
        updateUnpassable(messageManager);
        updateAllKnownRoad(messageManager);
        updateAllKnownBlockade(messageManager);
        // logger.debug("unpassable:" + unpassable);
    }

    /*
     * 用message更新Road和Blockade，不确定是否有用
     */
    private void updateAllKnownRoad(MessageManager messageManager) {
        

        for (CommunicationMessage message : messageManager.getReceivedMessageList()) {
            if (message.getClass() == MessageRoad.class) {
                MessageRoad mr = (MessageRoad) message;
                this.allKnownRoad.add(mr.getRoadID());
            }
        }
    }

    public void updateAllKnownBlockade(MessageManager messageManager){

        for (CommunicationMessage message : messageManager.getReceivedMessageList()) {
            if (message.getClass() == MessageRoad.class) {
                MessageRoad mr = (MessageRoad) message;
                this.allKnownBlockade.add(mr.getBlockadeID());
            }
        }
    
    }

    public void updateUnpassable(MessageManager messageManager){
        
        if (isBlocked()) {
            EntityID agentPosition = this.agentInfo.getPosition();
            EntityID nextPosition = null;
            if (this.agentInfo.getExecutedAction(agentInfo.getTime()-1).getClass() == ActionMove.class) {
                ActionMove actionMove = (ActionMove) this.agentInfo.getExecutedAction(agentInfo.getTime()-1);
                List<EntityID> path = actionMove.getPath();
                if (path.contains(agentPosition)&&path.size()>1) {
                    int index = path.indexOf(agentPosition);
                    nextPosition = path.get(index+1);
                }
            }

            if (nextPosition != null) {
                unpassable.put(agentPosition, nextPosition);
                unpassable.put(nextPosition, agentPosition);
            }
            if (getStayTime()>4) {
                logger.debug("-----stay too long time -----");
                stayTime.put(this.agentInfo.getTime(), 0);
                unpassable.clear();
            }
        }

    }

    private void updateMybadExit() {
        EntityID agentPosition = this.agentInfo.getPosition();
        if (this.worldInfo.getEntity(agentPosition)instanceof Building) {
            Building building = (Building) this.worldInfo.getEntity(agentPosition);
            Set<EntityID> exits = getAllEntrancesOfBuilding(building);
            for(EntityID e : exits){
                if (!(this.worldInfo.getEntity(e) instanceof Road)) {
                  continue;
                }
                Road road = (Road)this.worldInfo.getEntity(e);
                if (!road.isBlockadesDefined() || road.getBlockades().isEmpty()) {
                    continue;
                }
                MybadExit.add(e);
            }
        }
    }
    public Set<EntityID> getMybadExit(){
        return MybadExit;
    }
    private void updateAllBadEntrance(){
        Collection<StandardEntity> allbuildings = this.worldInfo.getEntitiesOfType(BUILDING);
        for(StandardEntity standardEntity : allbuildings) {
            if (!(standardEntity instanceof Building)) {
                continue;
            }
            Building building = (Building) standardEntity;
            Set<EntityID> entrances = getAllEntrancesOfBuilding(building);
            for(EntityID e : entrances){
                if (!(this.worldInfo.getEntity(e) instanceof Road)) {
                  continue;
                }
                Road road = (Road)this.worldInfo.getEntity(e);
                if (!road.isBlockadesDefined() || road.getBlockades().isEmpty()) {
                    continue;
                }
                allbadEntrance.add(e);
            }
        }

    }
    public Set<EntityID> getAllBadEntrance(){
        return allbadEntrance;
    }

    private void updateLocationHistory(){
        locationHistory.put(this.agentInfo.getTime(),this.worldInfo.getLocation(this.agentInfo.getID()));
    }

    private void undatePositionHistory(){
        positionHistory.put(this.agentInfo.getTime(), this.agentInfo.getPosition());
    }
    private void updateStayTime() {
        if (agentInfo.getTime()>1) {
            if (this.agentInfo.getExecutedAction(agentInfo.getTime()-1).getClass() == ActionMove.class) {
                Pair<Integer, Integer> location_2 = locationHistory.get(agentInfo.getTime() - 1);
                Pair<Integer, Integer> location_3 = locationHistory.get(agentInfo.getTime());
                double distance_2 = getDistance(location_2, location_3);
                if(distance_2<MinMoveDis){
                    int stay=0;
                    stay = stayTime.get(this.agentInfo.getTime()-1)+1;
                    stayTime.put(this.agentInfo.getTime(), stay);
                }
            }
        }
        stayTime.put(this.agentInfo.getTime(), 0);
    }

    public int getStayTime(){
        return this.stayTime.get(this.agentInfo.getTime());
    }
    
    public double getMoveDistance(){
        if (this.agentInfo.getExecutedAction(agentInfo.getTime()-1).getClass() == ActionMove.class) {
			Pair<Integer, Integer> location_2 = locationHistory.get(agentInfo.getTime() - 1);
			Pair<Integer, Integer> location_3 = locationHistory.get(agentInfo.getTime());
			if (location_2 == null || location_3 == null)
				return -1;
			double distance_2 = getDistance(location_2, location_3);
            return distance_2;
        }
        return -1;
    }

    /*
     * 以下代码用于判断智能体是否被阻挡
     */
    /*
     * 以下代码用于判断智能体是否被阻挡
     */
    /**
     * 判断智能体是否处于被阻挡状态
     * 该方法通过分析智能体的移动历史和当前状态来确定其是否被障碍物困住
     * @return 如果智能体被阻挡，返回true；否则返回false
     */
    public boolean isBlocked() {
        // 时间步数小于6时，认为智能体还未开始有效移动，不判定为被阻挡
        if (this.agentInfo.getTime() < 6)
            return false;

        // 警察单位不会被判定为被阻挡
        if (this.agentInfo.me() instanceof PoliceForce) {
            return false;
        }

        // 以下代码段被注释：原计划通过isHumanStucked方法判断智能体是否被困在障碍中
        // if (isHumanStucked((Human)this.agentInfo.me())) {
        //     logger.debug("-----被困在障碍中-----");
        //     return false;
        // }

        // 检查最近两步是否都执行了移动动作
        if (this.agentInfo.getExecutedAction(agentInfo.getTime()-1).getClass() == ActionMove.class &&
                this.agentInfo.getExecutedAction(agentInfo.getTime()-2).getClass() == ActionMove.class) {

            // 获取最近三个时间点的位置
            Pair<Integer, Integer> location_1 = locationHistory.get(agentInfo.getTime() - 2);
            Pair<Integer, Integer> location_2 = locationHistory.get(agentInfo.getTime() - 1);
            Pair<Integer, Integer> location_3 = locationHistory.get(agentInfo.getTime());

            // 如果任何一个位置为空，不判定为被阻挡
            if (location_1 == null || location_2 == null || location_3 == null)
                return false;

            // 计算相邻时间点之间的移动距离
            double distance_1 = getDistance(location_1, location_2);
            double distance_2 = getDistance(location_2, location_3);

            // 以下代码段被注释：用于调试时记录移动距离
            // logger.debug("-----moveDistance:" + distance_2 + "-----");

            // 如果最近一步的移动距离小于最小移动距离阈值，判定为被阻挡
            if (distance_2 < MinMoveDis) {
                return true;
            }
        }

        // 未满足被阻挡条件，返回false
        return false;
    }

    public static double getDistance(Pair<Integer, Integer> pair1, Pair<Integer, Integer> pair2) {
		int x1 = pair1.first().intValue();
		int y1 = pair1.second().intValue();
		int x2 = pair2.first().intValue();
		int y2 = pair2.second().intValue();
		return Math.hypot((x2 - x1) * 1.0, (y2 - y1) * 1.0);
    }


    /*
     * 以下代码用于判断Human是否困在障碍中或距离障碍过近
     */
    public boolean isInside(double x, double y, Blockade blockade) {
        return blockade.getShape().contains(x, y);
    }

    public boolean isNearBlockade(double x, double y, Blockade blockade, double range) {
        int[] apex = blockade.getApexes();
        for (int i = 0; i < apex.length - 4; i += 2) {
            if(java.awt.geom.Line2D.ptLineDist(apex[i], apex[i + 1], apex[i + 2], apex[i + 3], x, y) < range) {
                return true;
            }
        }
        if (java.awt.geom.Line2D.ptLineDist(apex[0], apex[1], apex[apex.length - 2], apex[apex.length - 1], x, y) < range) {
            return true;
        }
        return false;
    }

    public boolean isHumanStucked(Human human, Blockade blockade) {
        int x = human.getX();
        int y = human.getY();
        return this.isInside(x, y, blockade);
    }

    public boolean isHumanStucked(Human human, Area area) {
        if (!area.isBlockadesDefined() || area.getBlockades().isEmpty()) {
            return false;
        }
        for (Blockade blockade : this.worldInfo.getBlockades(area)) {
            if (this.isHumanStucked(human, blockade)) {
                return true;
            }
        }
        return false;
    }

    public boolean isHumanStucked(Human human) {
        StandardEntity entity = this.worldInfo.getPosition(human);
        if (entity instanceof Area) {
            Area area = (Area)entity;
            if (!(this.agentInfo.me() instanceof PoliceForce)) {
                if (isHumanStucked(human, area)) {
                    return true;
                }
            }
        }
        return false;
    }

    public Set<EntityID> getAllEntrancesOfBuilding(Building building) {
        EntityID buildingID = building.getID();
        Set<EntityID> entrances = new HashSet<>();
        Set<EntityID> visited = new HashSet<>();
        Stack<EntityID> stack = new Stack<>();
        stack.push(buildingID);
        visited.add(buildingID);
        while (!stack.isEmpty()) {
            EntityID id = stack.pop();
            visited.add(id);
            Area area = (Area)this.worldInfo.getEntity(id);
            if (area instanceof Road) {
                entrances.add(id);
            } else {
                for (EntityID neighbor : area.getNeighbours()) {
                    if (visited.contains(neighbor)) {
                        continue;
                    }
                    stack.push(neighbor);
                }
            }
        }
        return entrances;
    }

    /*
     * 以下代码用于计算路径的总代价, 不确定是否正确
     */
    public double getCost(List<EntityID> path, boolean weight) {
        double cost = 0.0;
        int theold = 1;
        if (weight) {
            theold =10000;
        }
        if (path.size()>1) {
            for(int i=0;i<path.size()-1;i++) {
                if (unpassable.get(path.get(i)) == path.get(i+1)) {
                    cost += this.worldInfo.getDistance(path.get(i), path.get(i+1))/100*theold;
                }
                else{
                    cost += this.worldInfo.getDistance(path.get(i), path.get(i+1));
                }
            }
        }
        return cost;
    }
    
}
