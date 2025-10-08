package SEU.world;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.MessageUtil;
import adf.core.agent.communication.standard.bundle.StandardMessage;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;
import adf.core.agent.communication.standard.bundle.centralized.CommandAmbulance;
import adf.core.agent.communication.standard.bundle.information.MessageCivilian;
import adf.core.agent.communication.standard.bundle.information.MessageRoad;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.AbstractModule;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;
import java.util.stream.Collectors;






public class SEUWorldInfo extends AbstractModule {

    /*
        街道
    */
    private Map<Integer, SEURoad> SEURoads = new HashMap<>();
    private Map<EntityID, Integer> areaID2SEUID = new HashMap<>();

    /*
        小区
    */
    private Map<Integer, SEUNeighborhood> SEUNeighborhoods = new HashMap<>();
    /*
    地图上所有Area里EntityID最大的ID数值
    收发消息时ID小于等于该值的为地图上正常的Area
    ID大于该值且消息类型为MessageRoad表示整条街道的状态
    对应的街道编号为ID-maxAreaID
    */
    private Integer maxAreaID;

    /*
        预计算存储key
    */
    private final String KEY_CONNECTEDAREAS = "SEU.SEUWorldInfo.connectedAreas";
    private final String KEY_TAGS = "SEU.SEUWorldInfo.tags";
    private final String KEY_NEIGHBORHOODS = "SEU.SEUWorldInfo.neighborhoods";
    private final String KEY_NEIGHBORHOODS_CENTER = "SEU.SEUWorldinfo.neighborhoodsCenters";
    private final String KEY_NEIGHBORHOODS_SPECIAL = "SEU.SEUWorldInfo.neighborhoodsSpecial";
    private final String KEY_NEIGHBORHOODS_EDGES = "SEU.SEUWorldInfo.neighborhoodsEdge";
    private final String KEY_NEIGHBORHOODS_ROADS = "SEU.SEUWorldInfo.neighborhoodsRoads";

    //oym
    private final String Key_BuildingArea = "SEU.SEUWorldInfo.BuildingArea";
    
    /*
        求救信号发送记录与拥塞控制常数
    */
    private int lastRequestTime;
    private final int sendingAvoidTimeRequest = 3;

    private final int sendindAvoidTimeReceive = 2;
    private Map<EntityID, Integer> receivedTime = new HashMap<>();

    private SEUGeomTool geom;

    private double BuildingArea;
    
    public SEUWorldInfo(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);

        this.lastRequestTime = -this.sendingAvoidTimeRequest;														//@何种用途？？？？？？？？

        this.geom = moduleManager.getModule("SEUWorldInfo.GeomTool", "SEU.world.SEUGeomTool");
        registerModule(this.geom);

    }

    public SEUWorldInfo precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        if (this.getCountPrecompute() >= 2) {
            return this;
        }
        //todo: precompute
        this.precomputeSEURoads(precomputeData);
        this.precomputeSEUNeighborhoods(precomputeData);
        this.precomputeBuildingArea(precomputeData);
        return this;
    }

    public SEUWorldInfo resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) {
            return this;
        }
        //todo: resume
        this.resumeSEURoads(precomputeData);
        this.resumeSEUNeighborhoods(precomputeData);
        this.resumeBuildingArea(precomputeData);
        //this.printSEURoads();
        //this.printSEUNeighborhoods();

        this.maxAreaID = Integer.MIN_VALUE;																	//@得到EntityID   value 最大值，以此来确认地图大小，需要测试！
        for (StandardEntity e : this.worldInfo.getAllEntities()) {
            if ((e instanceof Area) && (e.getID().getValue() > this.maxAreaID)) this.maxAreaID = e.getID().getValue();	//@Area是什么？为什么非得是Area
        }
        ++this.maxAreaID;
        return this;
    }

    public SEUWorldInfo preparate() {
        super.preparate();
        if (this.getCountPreparate() >= 2) {
            return this;
        }
        //todo: preparate
        return this;
    }

    public SEUWorldInfo updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) {
            return this;
        }
        //todo: updateInfo																										//@学会此种用法
        this.sendRequestCommand(messageManager);
        this.reflectMessage(messageManager);
        this.sendInformationMessage(messageManager);
        //this.updateSEURoads(messageManager);
        
        return this;
    }

    public SEUWorldInfo calc() {
        //todo: calc
        return this;
    }

    //getter

    /*
        得到Area所属的街道的编号
    */
    public int getSEURoadID(EntityID id) {
        return this.areaID2SEUID.get(id);
    }

    /*
        得到编号对应的街道
    */
    public SEURoad getSEURoad(int id) {
        return this.SEURoads.get(id);
    }

    /*
        得到Area所属的街道
    */
    public SEURoad getSEURoad(EntityID id) {
        return this.SEURoads.get(this.areaID2SEUID.get(id));
    }

    /*
        得到所有SEURoad
    */
    public Set<SEURoad> getAllSEURoads() {
        return new HashSet<>(this.SEURoads.values());
    }

    /*
        得到特定类型的SEURoad
    */
    public Set<SEURoad> getSEURoads(int type) {
        return this.SEURoads.values().stream().filter(seuRoad -> seuRoad.getType() == type).collect(Collectors.toSet());
    }

    /*
        得到maxAreaID
    */
    public int getMaxAreaID() {
        return this.maxAreaID;
    }

    /*
        得到所有SEUNeighborhood
    */
    public List<SEUNeighborhood> getAllSEUNeighborhoods() {
        return new LinkedList<>(this.SEUNeighborhoods.values());
    }



    //precompute

    /*
        连接同类型的Area，并写入预计算数据
    */
    private void precomputeSEURoads(PrecomputeData precomputeData) {
        Set<EntityID> branchAreas = new HashSet<>();
        Set<EntityID> ringAreas = new HashSet<>();
        Set<EntityID> crossAreas = new HashSet<>();

        this.classifyAreas(branchAreas, ringAreas, crossAreas);

        Set<List<EntityID>> deadend = new HashSet<>();
        Set<List<EntityID>> branch = this.connectOtherAreas(branchAreas);
        Set<List<EntityID>> ring = this.connectRingAreas(ringAreas, crossAreas);
        Set<List<EntityID>> cross = this.connectOtherAreas(crossAreas);

        for (List<EntityID> ids : branch) {
            boolean haveBuilding = false;
            for (EntityID id : ids) {
                if (this.worldInfo.getEntity(id) instanceof Building) {
                    haveBuilding = true;
                    break;
                }
            }
            if (!haveBuilding) deadend.add(ids);
        }
        branch.removeAll(deadend);

        List<Integer> tags = new ArrayList<>();
        int key = 0;
        for (List<EntityID> ids : deadend) precomputeData.setEntityIDList(this.KEY_CONNECTEDAREAS + key++, ids);
        tags.add(key);
        for (List<EntityID> ids : branch) precomputeData.setEntityIDList(this.KEY_CONNECTEDAREAS + key++, ids);
        tags.add(key);
        for (List<EntityID> ids : ring) precomputeData.setEntityIDList(this.KEY_CONNECTEDAREAS + key++, ids);
        tags.add(key);
        for (List<EntityID> ids : cross) precomputeData.setEntityIDList(this.KEY_CONNECTEDAREAS + key++, ids);
        tags.add(key);

        precomputeData.setIntegerList(this.KEY_TAGS, tags);
    }

    /*
        根据连通性给所有Area分类，分为：
        BranchArea——连接了0对不相邻的非BranchArea类Area
        Deadend为特殊的BranchArea，连通性与BranchArea一致，但Deadend中没有Building只有Road
        RingArea——连接了1对不相邻的非BranchArea类Area
        CrossArea——连接了1对以上不相邻的非BranchArea类Area
    */
    private void classifyAreas(Set<EntityID> branchAreas, Set<EntityID> ringAreas, Set<EntityID> crossAreas) {
        for (StandardEntity entity : this.worldInfo.getAllEntities()) {
            if (!(entity instanceof Area)) continue;
            if (this.calcConnection((Area) entity, branchAreas) < 1) branchAreas.add(entity.getID());
        }
        Queue<EntityID> queue = new LinkedList<>();
        for (EntityID id : branchAreas) queue.offer(id);
        while (!queue.isEmpty()) {
            Area area = (Area) this.worldInfo.getEntity(queue.poll());
            for (EntityID id : new HashSet<>(area.getNeighbours())) {
                if (branchAreas.contains(id)) continue;
                int connection = this.calcConnection((Area) this.worldInfo.getEntity(id), branchAreas);
                if (connection < 1) {
                    ringAreas.remove(id);
                    branchAreas.add(id);
                    queue.offer(id);
                } else if (connection == 1) {
                    crossAreas.remove(id);
                    ringAreas.add(id);
                } else crossAreas.add(id);
            }
        }
        for (StandardEntity entity : this.worldInfo.getAllEntities()) {
            if (!(entity instanceof Area)) continue;
            EntityID id = entity.getID();
            if (branchAreas.contains(id) || ringAreas.contains(id) || crossAreas.contains(id)) continue;
            int connection = this.calcConnection((Area) entity, branchAreas);
            if (connection < 1) branchAreas.add(id);
            else if (connection == 1) ringAreas.add(id);
            else crossAreas.add(id);
        }
    }

    /*
        计算输入的Area连接了多少对不相邻的非branch类Area
    */
    private int calcConnection(Area area, Set<EntityID> branchAreas) {
        int connection = 0;
        Set<EntityID> neighbors = new HashSet<>(area.getNeighbours());
        List<EntityID> removeList = new ArrayList<>();
        for (EntityID nID : neighbors) { //
            if (branchAreas.contains(nID)) removeList.add(nID);
        }
        neighbors.removeAll(removeList);
        if (neighbors.size() < 2) return connection;
        for (EntityID nID : neighbors) {
            Area neighbor = (Area) this.worldInfo.getEntity(nID);
            for (EntityID neighborID : new HashSet<>(neighbor.getNeighbours())) {
                if (neighbors.contains(neighborID)) --connection;
            }
        }
        connection /= 2;
        connection += neighbors.size()*(neighbors.size() - 1)/2;
        return connection;
    }

    /*
        把同类的Area连接成一条路，并保证连接起来的Area有序
    */
    private Set<List<EntityID>> connectRingAreas(Set<EntityID> ringAreas, Set<EntityID> crossAreas) {
        Set<List<EntityID>> result = new HashSet<>();
        Set<EntityID> connected = new HashSet<>();
        for (EntityID id : ringAreas) {
            if (connected.contains(id)) continue;
            boolean isEdge = false;
            for (EntityID neighborID : new HashSet<>(((Area) this.worldInfo.getEntity(id)).getNeighbours())) {
                if (crossAreas.contains(neighborID)) {
                    isEdge = true;
                    break;
                }
            }
            if (!isEdge) continue;
            Queue<EntityID> queue = new LinkedList<>();
            Set<EntityID> visited = new HashSet<>();
            List<EntityID> roads = new LinkedList<>();
            queue.offer(id);
            roads.add(id);
            visited.add(id);
            while (!queue.isEmpty()) {
                for (EntityID neighborID : new HashSet<>(((Area) this.worldInfo.getEntity(queue.poll())).getNeighbours())) {
                    if (!ringAreas.contains(neighborID) || visited.contains(neighborID)) continue;
                    roads.add(neighborID);
                    queue.offer(neighborID);
                    visited.add(neighborID);
                }
            }
            result.add(roads);
            connected.addAll(visited);
        }
        return result;
    }

    /*
        把同类的Area链接成一块，不关心连接起来的Area的顺序
    */
    private Set<List<EntityID>> connectOtherAreas(Set<EntityID> sameTypeAreas) {
        Set<List<EntityID>> result = new HashSet<>();
        Set<EntityID> connected = new HashSet<>();
        for (EntityID id : sameTypeAreas) {
            if (connected.contains(id)) continue;
            Queue<EntityID> queue = new LinkedList<>();
            Set<EntityID> visited = new HashSet<>();
            List<EntityID> roads = new LinkedList<>();
            queue.offer(id);
            roads.add(id);
            visited.add(id);
            while (!queue.isEmpty()) {
                for (EntityID neighborID : new HashSet<>(((Area) this.worldInfo.getEntity(queue.poll())).getNeighbours())) {
                    if (!sameTypeAreas.contains(neighborID) || visited.contains(neighborID)) continue;
                    roads.add(neighborID);
                    queue.offer(neighborID);
                    visited.add(neighborID);
                }
            }
            result.add(roads);
            connected.addAll(visited);
        }
        return result;
    }

    /*
        todo:筛选出小区内所有buildingID、specialID（消防站、警局、医院、加油站、避难所）、
        				edgeBuildings(小区边缘buildingID、neighborRoadID(小区周围道路),并将这些数据存入预计算数据库
    */
    private void precomputeSEUNeighborhoods(PrecomputeData precomputeData) {
        List<List<Building>> neighborhoods = this.calcNeighborhoods();
        int key = 0;
        for (List<Building> buildings : neighborhoods) {
            List<EntityID> buildingIDs = new ArrayList<>();
            List<EntityID> special = new ArrayList<>();
            List<EntityID> edgeBuildings = new ArrayList<>();
            List<EntityID> neighborRoads = new ArrayList<>();
            for (Building building : buildings) {
                buildingIDs.add(building.getID());
                if (building instanceof AmbulanceCentre || building instanceof FireStation ||
                        building instanceof GasStation || building instanceof PoliceOffice ||
                        building instanceof Refuge) special.add(building.getID());
                for (EntityID id : this.geom.calcNeighbors(building)) {
                    if (this.worldInfo.getEntity(id) instanceof Road) {
                        edgeBuildings.add(building.getID());
                        break;
                    }
                }
                neighborRoads.addAll(this.geom.getNeighborRoads(building));
            }
            precomputeData.setEntityIDList(this.KEY_NEIGHBORHOODS + key, buildingIDs);
            Point2D cp = this.calcCenterOfNeighborhood(buildings);
            precomputeData.setDouble(this.KEY_NEIGHBORHOODS_CENTER + "X" + key, cp.getX());
            precomputeData.setDouble(this.KEY_NEIGHBORHOODS_CENTER + "Y" + key, cp.getY());
            precomputeData.setEntityIDList(this.KEY_NEIGHBORHOODS_SPECIAL + key, special);
            precomputeData.setEntityIDList(this.KEY_NEIGHBORHOODS_EDGES + key, edgeBuildings);
            precomputeData.setEntityIDList(this.KEY_NEIGHBORHOODS_ROADS + key, neighborRoads);
            ++key;
        }
        precomputeData.setInteger(this.KEY_NEIGHBORHOODS + "size", key);
    }

    /*
     * to do: 将地图所有建筑物划分为一个个小区
     */
    
    private List<List<Building>> calcNeighborhoods()
    {
        List<EntityID> UnClusteredBuildingIDs = new ArrayList<>(this.worldInfo.getEntityIDsOfType(
                StandardEntityURN.BUILDING, StandardEntityURN.AMBULANCE_CENTRE, StandardEntityURN.FIRE_STATION,
                StandardEntityURN.GAS_STATION, StandardEntityURN.POLICE_OFFICE, StandardEntityURN.REFUGE));
        List<List<Building>> ResultNeighborhoods = new LinkedList<>();
        while(!UnClusteredBuildingIDs.isEmpty())
        {
            Building building = (Building) this.worldInfo.getEntity(UnClusteredBuildingIDs.get(0));
            List<Building> neighborhood = new LinkedList<>();
            Queue<Building> NeighborQueue = new LinkedList<>();
            NeighborQueue.add(building);
            while(!NeighborQueue.isEmpty())
            {
                Building headBuilding = NeighborQueue.peek();
                for(EntityID entityid : this.geom.getNeighborBuildings(headBuilding))
                {
                    Building bu = (Building) this.worldInfo.getEntity(entityid);
                    if(NeighborQueue.contains(bu) == false && UnClusteredBuildingIDs.contains(entityid))
                    {
                        NeighborQueue.add(bu);
                    }
                }
                UnClusteredBuildingIDs.remove(headBuilding.getID());
                neighborhood.add(NeighborQueue.poll());
            }
            ResultNeighborhoods.add(neighborhood);
        }
        return ResultNeighborhoods;
    }

    private Point2D calcCenterOfNeighborhood(List<Building> neighborhood) {
        double totalArea = 0;
        double cx = 0;
        double cy = 0;
        for (Building building : neighborhood) totalArea += building.getTotalArea();
        for (Building building : neighborhood) {
            double property = (double) building.getTotalArea()/totalArea;
            cx += property*building.getX();
            cy += property*building.getY();
        }
        return new Point2D(cx, cy);
    }

    //resume

    /*
        从预计算数据库读取数据并初始化SEURoads
    */
    private void resumeSEURoads(PrecomputeData precomputeData) {
        List<Integer> tags = precomputeData.getIntegerList(this.KEY_TAGS);
        int deadendTag = tags.get(0);
        int branchTag = tags.get(1);
        int ringTag = tags.get(2);
        int crossTag = tags.get(3);
        //todo: 尽量把计算往预计算部分扔
        for (int i = 0;i < crossTag; ++i) {
            SEURoad seuRoad = new SEURoad(i);
            List<EntityID> areas = precomputeData.getEntityIDList(this.KEY_CONNECTEDAREAS + i);
            Set<EntityID> neighbors = new HashSet<>();
            Set<Edge> openEdges = new HashSet<>();
            Set<Edge> innerEdges = new HashSet<>();
            for (EntityID id : areas) {
                this.areaID2SEUID.put(id, i);
                Area area = (Area) this.worldInfo.getEntity(id);
                for (Edge edge : area.getEdges()) {
                    if (!edge.isPassable()) continue;
                    EntityID neighborID = edge.getNeighbour();
                    if (areas.contains(neighborID)) innerEdges.add(edge);
                    else {
                        openEdges.add(edge);
                        neighbors.add(neighborID);
                    }
                }
            }
            seuRoad.setAreas(areas);
            seuRoad.setNeighbors(neighbors);
            seuRoad.setOpenEdges(openEdges);
            seuRoad.setInnerEdges(innerEdges);
            this.SEURoads.put(i, seuRoad);
        }

        for (int i = 0;i < deadendTag;++i) this.SEURoads.get(i).setType(SEURoad.DeadEnd);
        for (int i = deadendTag;i < branchTag;++i) this.SEURoads.get(i).setType(SEURoad.BranchRoad);
        for (int i = branchTag;i < ringTag;++i) this.SEURoads.get(i).setType(SEURoad.RingRoad);
        for (int i = ringTag;i < crossTag;++i) this.SEURoads.get(i).setType(SEURoad.CrossRoad);

        for (Map.Entry<Integer, SEURoad> kv : this.SEURoads.entrySet()) {
            Set<Integer> neighborSEURoads = new HashSet<>();
            for (EntityID id : kv.getValue().getNeighbors()) neighborSEURoads.add(this.areaID2SEUID.get(id));
            kv.getValue().setNeighborSEURoads(neighborSEURoads);
        }
    }

    /*
        读取数据并初始化SEUNeighborhood
    */
    private void resumeSEUNeighborhoods(PrecomputeData precomputeData) {
        int size = precomputeData.getInteger(this.KEY_NEIGHBORHOODS + "size");
        for (int i = 0;i < size; ++i) {
            SEUNeighborhood seuNeighborhood = new SEUNeighborhood();
            seuNeighborhood.SetID(i);
            List<Building> buildings = new LinkedList<>();
            for (EntityID id : precomputeData.getEntityIDList(this.KEY_NEIGHBORHOODS + i)) {
                buildings.add((Building) this.worldInfo.getEntity(id));
            }
            seuNeighborhood.SetNeighborhoodBuildings(buildings);
            seuNeighborhood.SetCenter(
                    new Point2D(precomputeData.getDouble(this.KEY_NEIGHBORHOODS_CENTER + "X" + i),
                            precomputeData.getDouble(this.KEY_NEIGHBORHOODS_CENTER + "Y" + i)));
            seuNeighborhood.SetRoads(precomputeData.getEntityIDList(this.KEY_NEIGHBORHOODS_ROADS + i));
            buildings.clear();
            for (EntityID id : precomputeData.getEntityIDList(this.KEY_NEIGHBORHOODS_EDGES + i)) {
                buildings.add((Building) this.worldInfo.getEntity(id));
            }
            seuNeighborhood.SetEdges(buildings);
            seuNeighborhood.SetSpecialIDs(precomputeData.getEntityIDList(this.KEY_NEIGHBORHOODS_SPECIAL));
            this.SEUNeighborhoods.put(i, seuNeighborhood);
        }
    }

    //updateInfo

    /*
        发送求救信息
    */
    private void sendRequestCommand(MessageManager messageManager) {
        if (this.agentInfo.getTime() < this.lastRequestTime + this.sendingAvoidTimeRequest) return;
        Human me = (Human) this.agentInfo.me();
        if (me.isBuriednessDefined() && me.getBuriedness() > 0) {
            messageManager.addMessage(
                    new CommandAmbulance(true, null, this.agentInfo.getPosition(), CommandAmbulance.ACTION_RESCUE));
        }
        //todo: 如果在楼里且楼入口被堵住，向警察发送CommandPolice
    }

    /*
        根据接收到的信息更新世界模型
    */
    private void reflectMessage(MessageManager messageManager) {
        for (CommunicationMessage message : messageManager.getReceivedMessageList(StandardMessage.class)) {
            Class<? extends StandardMessage> messageClass = ((StandardMessage) message).getClass();
            if (messageClass != MessageRoad.class) {
                StandardEntity entity = MessageUtil.reflectMessage(this.worldInfo, (StandardMessage) message);
                if (entity != null) this.receivedTime.put(entity.getID(), this.agentInfo.getTime());
            } else if (((MessageRoad) message).getRoadID().getValue() < this.maxAreaID) {
                StandardEntity entity = MessageUtil.reflectMessage(this.worldInfo, (MessageRoad) message);
                if (entity != null) this.receivedTime.put(entity.getID(), this.agentInfo.getTime());
            }
        }
    }

    /*
        更新SEURoad
    */
//    private void updateSEURoads(MessageManager messageManager) {
//        //get human on seuroad
//        Map<Integer, Set<EntityID>> onRoadCivilian = new HashMap<>();
//        for (EntityID id : this.worldInfo.getEntityIDsOfType(StandardEntityURN.CIVILIAN)) {
//            if (!((Human) this.worldInfo.getEntity(id)).isPositionDefined()) continue;
//            int seuPosition = this.areaID2SEUID.get(this.worldInfo.getPosition(id).getID());
//            onRoadCivilian.putIfAbsent(seuPosition, new HashSet<>());
//            onRoadCivilian.get(seuPosition).add(id);
//        }
//        Map<Integer, Set<EntityID>> onRoadAgent = new HashMap<>();
//        for (EntityID id : this.worldInfo.getEntityIDsOfType(StandardEntityURN.AMBULANCE_TEAM, StandardEntityURN.FIRE_BRIGADE)) {
//            if (!((Human) this.worldInfo.getEntity(id)).isPositionDefined()) continue;
//            int seuPosition = this.areaID2SEUID.get(this.worldInfo.getPosition(id).getID());
//            onRoadAgent.putIfAbsent(seuPosition, new HashSet<>());
//            onRoadAgent.get(seuPosition).add(id);
//        }
//
//        for (SEURoad seuRoad : this.SEURoads.values()) {
//            //update blockades
//            for (EntityID id : seuRoad.getAreas()) {
//                seuRoad.setBlockades(id, this.worldInfo.getBlockades(id).stream().collect(Collectors.toSet()));
//            }
//            //update stucked human
//            Set<Blockade> blockades = seuRoad.getAllBlockades();
//            Map<EntityID, Set<EntityID>> stuckedCivilian = new HashMap<>();
//            for (EntityID humanID : onRoadCivilian.getOrDefault(seuRoad.getId(), new HashSet<>())) {
//                Human human = (Human) this.worldInfo.getEntity(humanID);
//                if (!human.isXDefined() || !human.isYDefined()) continue;
//                for (Blockade blockade : blockades) {
//                    if (this.geom.isHumanStucked(human, blockade)) {
//                        stuckedCivilian.putIfAbsent(human.getPosition(), new HashSet<>());
//                        stuckedCivilian.get(human.getPosition()).add(humanID);
//                        break;
//                    }
//                }
//            }
//            seuRoad.setStuckedCivilian(stuckedCivilian);
//            Map<EntityID, Set<EntityID>> stuckedAgent = new HashMap<>();
//            for (EntityID humanID : onRoadAgent.getOrDefault(seuRoad.getId(), new HashSet<>())) {
//                Human human = (Human) this.worldInfo.getEntity(humanID);
//                if (!human.isXDefined() || !human.isYDefined()) continue;
//                for (Blockade blockade : blockades) {
//                    if (this.geom.isHumanStucked(human, blockade)) {
//                        stuckedAgent.putIfAbsent(human.getPosition(), new HashSet<>());
//                        stuckedAgent.get(human.getPosition()).add(humanID);
//                        break;
//                    }
//                }
//            }
//            seuRoad.setStuckedAgent(stuckedAgent);
//        }
//        //update stuckedagent from commandpolice
//        for (CommunicationMessage message : messageManager.getReceivedMessageList(CommandPolice.class)) {
//            CommandPolice commandPolice = (CommandPolice) message;
//            if (commandPolice.isToIDDefined()) continue;
//            if (!commandPolice.isTargetIDDefined()) continue;
//            EntityID senderID = commandPolice.getSenderID();
//            EntityID targetID = commandPolice.getTargetID();
//            if (this.worldInfo.getEntity(senderID) instanceof Human) {
//                this.SEURoads.get(this.areaID2SEUID.get(targetID)).addStuckedAgent(targetID, senderID);
//            }
//        }
//        //todo: 表示整条街道状态的消息的处理
//        /*
//        for (CommunicationMessage message : messageManager.getReceivedMessageList(MessageRoad.class)) {
//            MessageRoad messageRoad = (MessageRoad) message;
//            int seuID = messageRoad.getRoadID().getValue() - this.maxAreaID;
//            if (seuID < 0) continue;
//
//        }
//        */
//    }

    /*
        发送看到的信息
    */
    private void sendInformationMessage(MessageManager messageManager) {
        for (EntityID id : this.worldInfo.getChanged().getChangedEntities()) {
            if (this.receivedTime.getOrDefault(id, -this.sendindAvoidTimeReceive) + this.sendindAvoidTimeReceive
                    > this.agentInfo.getTime()) continue;
            StandardEntity e = this.worldInfo.getEntity(id);
/*            if (e instanceof Building) {
                Building b = (Building) e;
                if (b.isOnFire()) {
                    messageManager.addMessage(new MessageBuilding(true, StandardMessagePriority.HIGH, b));
                } else {
                    messageManager.addMessage(new MessageBuilding(true, StandardMessagePriority.NORMAL, b));
                }

            }
 */
            if (e instanceof Civilian) {
                Civilian c = (Civilian) e;
                if (c.isDamageDefined() && c.getDamage() > 0) {
                    messageManager.addMessage(new MessageCivilian(true, StandardMessagePriority.HIGH, c));
                }
                else if (c.isBuriednessDefined() && c.getBuriedness() > 0) {
                    messageManager.addMessage(new MessageCivilian(true, StandardMessagePriority.HIGH, c));
                }
            }
        }
    }
//Building
//author:OYM
    private void precomputeBuildingArea(PrecomputeData precomputeData)
    {
  
    	double allBuildingArea = 0;
    	  for (StandardEntity entity : this.worldInfo.getAllEntities()) 
    	  {
    		  if(entity instanceof Building)
    		  	{
    			  	Building building  = (Building)entity;
    			  	allBuildingArea += building.getTotalArea();
    		  	} 		  
    	  }
//    	  System.out.println("11111111111预计算！！！！！！：：：    "+allBuildingArea);
    		precomputeData.setDouble(Key_BuildingArea, allBuildingArea);
    }
    private void resumeBuildingArea(PrecomputeData precomputeData)
    {
       
      this.BuildingArea = precomputeData.getDouble(Key_BuildingArea);
 //     System.out.println("22222222222还原数据！！！！！！：：：    "+this.BuildingArea);
      }
    public double getBuildingArea()
    {
    	return this.BuildingArea;
    }
    
    
    
    
    //debug

    private void printSEURoads() {
        for (SEURoad seuRoad : this.SEURoads.values()) System.out.println(seuRoad);
    }

    private void printSEUNeighborhoods() {
        for (SEUNeighborhood seuNeighborhood : this.SEUNeighborhoods.values()) System.out.println(seuNeighborhood);
    }
}
