package SEU.world;

import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.Edge;
import rescuecore2.worldmodel.EntityID;

import java.util.*;


public class SEURoad {

    /*
        静态成员，在初始化时赋值
    */
    private int id;                                  //街道编号
    private List<EntityID> areas;                    //街道包含的所有area的id
    private Set<Edge> openEdges;                     //通往其它街道的edge
    private Set<Edge> innerEdges;                    //内部的edge
    private Set<EntityID> neighbors;                 //与街道相邻的area的id
    private Set<Integer> neighborSEURoads;           //与街道相邻的街道的编号
    private int type;                                //街道类型

    /*
        街道类型常数
    */
    public static final int Unknow = 0;
    public static final int DeadEnd = 1;
    public static final int BranchRoad = 2;
    public static final int RingRoad = 3;
    public static final int CrossRoad = 4;

    /*
        动态成员，每周期更新
    */
    private Set<Edge> passableEdges;                      //可通过的edge，需考虑路障
    private Map<EntityID, Set<Blockade>> blockades;       //街道上的blockade，key为area的id，value为blockades
    private Map<EntityID, Set<EntityID>> stuckedCivilian; //被困在街上的平民，key为area的id，value为civilian的id
    private Map<EntityID, Set<EntityID>> stuckedAgent;    //被困在街上的智能体，key为area的id，value为agent的id


    public SEURoad(int id) {
        this.id = id;
        this.areas = new ArrayList<>();
        this.openEdges = new HashSet<>();
        this.passableEdges = new HashSet<>();
        this.innerEdges = new HashSet<>();
        this.neighbors = new HashSet<>();
        this.neighborSEURoads = new HashSet<>();
        this.type = SEURoad.Unknow;
        this.blockades = new HashMap<>();
        this.stuckedCivilian = new HashMap<>();
        this.stuckedAgent = new HashMap<>();
    }

    public int getId() {
        return id;
    }

    public List<EntityID> getAreas() {
        return areas;
    }

    public Set<Edge> getOpenEdges() {
        return openEdges;
    }

    public Set<Edge> getPassableEdges() {
        return passableEdges;
    }

    public Set<Edge> getInnerEdges() {
        return innerEdges;
    }

    public Set<EntityID> getNeighbors() {
        return neighbors;
    }

    public Set<Integer> getNeighborSEURoads() {
        return neighborSEURoads;
    }

    public Map<EntityID, Set<Blockade>> getBlockades() {
        return blockades;
    }

    public Set<Blockade> getAllBlockades() {
        Set<Blockade> ab = new HashSet<>();
        for (Set<Blockade> blockades : this.blockades.values()) ab.addAll(blockades);
        return ab;
    }

    public boolean haveBlockade() {
        for (Set<Blockade> blockades : this.blockades.values()) {
            if (!blockades.isEmpty()) return true;
        }
        return false;
    }

    public Map<EntityID, Set<EntityID>> getStuckedCivilian() {
        return stuckedCivilian;
    }

    public Set<EntityID> getStuckedCivilian(EntityID areaID) {
        return this.stuckedCivilian.get(areaID);
    }

    public Map<EntityID, Set<EntityID>> getStuckedAgent() {
        return stuckedAgent;
    }

    public Set<EntityID> getStuckedAgent(EntityID areaID) {
        return this.stuckedAgent.get(areaID);
    }

    public Set<EntityID> getAllStuckedCivilian() {
        Set<EntityID> asc = new HashSet<>();
        for (Set<EntityID> ids : this.stuckedCivilian.values()) asc.addAll(ids);
        return asc;
    }

    public Set<EntityID> getAllStuckedAgent() {
        Set<EntityID> asa = new HashSet<>();
        for (Set<EntityID> ids : this.stuckedAgent.values()) asa.addAll(ids);
        return asa;
    }

    public int getNumOfStuckedCivilian() {
        int nc = 0;
        for (Set<EntityID> ids : this.stuckedCivilian.values()) nc += ids.size();
        return nc;
    }

    public int getNumOfStuckedAgent() {
        int na = 0;
        for (Set<EntityID> ids : this.stuckedAgent.values()) na += ids.size();
        return na;
    }

    public int getType() {
        return type;
    }

    public void setId(int id) {
        this.id = id;
    }

    public void setAreas(List<EntityID> areas) {
        this.areas = areas;
    }

    public void setNeighbors(Set<EntityID> neighbors) {
        this.neighbors = neighbors;
    }

    public void setNeighborSEURoads(Set<Integer> neighborSEURoads) {
        this.neighborSEURoads = neighborSEURoads;
    }

    public void setOpenEdges(Set<Edge> openEdges) {
        this.openEdges = openEdges;
    }

    public void setPassableEdges(Set<Edge> passableEdges) {
        this.passableEdges = passableEdges;
    }

    public void setInnerEdges(Set<Edge> innerEdges) {
        this.innerEdges = innerEdges;
    }

    public void setType(int type) {
        this.type = type;
    }

    public void setBlockades(Map<EntityID, Set<Blockade>> blockades) {
        this.blockades = blockades;
    }

    public void setBlockades(EntityID areaID, Set<Blockade> blockades) {
        this.blockades.put(areaID, blockades);
    }

    public void setStuckedCivilian(Map<EntityID, Set<EntityID>> stuckedCivilian) {
        this.stuckedCivilian = stuckedCivilian;
    }

    public void setStuckedAgent(Map<EntityID, Set<EntityID>> stuckedAgent) {
        this.stuckedAgent = stuckedAgent;
    }

    public void addStuckedAgent(EntityID areaID, EntityID agentID) {
        this.stuckedAgent.putIfAbsent(areaID, new HashSet<>());
        this.stuckedAgent.get(areaID).add(agentID);
    }

    @Override
    public String toString() {
        String s = "";
        s += "ID: " + this.id + "\n";
        s += "Areas: ";
        for (EntityID id : this.areas) s += id.toString() + ",";
        s += "\n";
        s += "Neighbors: ";
        for (EntityID id : this.neighbors) s += id.toString() + ",";
        s += "\n";
        s += "NeighborSEURoads: ";
        for (int i : this.neighborSEURoads) s += i + ",";
        s += "\n";
        s += "Type: ";
        switch (this.type) {
            case SEURoad.Unknow:
                s += "Unknow \n";
                break;
            case SEURoad.DeadEnd:
                s += "Deadend \n";
                break;
            case SEURoad.BranchRoad:
                s += "BranchRoad \n";
                break;
            case SEURoad.RingRoad:
                s += "RingRoad \n";
                break;
            case SEURoad.CrossRoad:
                s += "CrossRoad \n";
                break;
        }
        return s;
    }

    @Override
    public int hashCode() {
        return ((Integer) this.id).hashCode();
    }
}
