package SEU.world;
/*
 * 本文件封装了SEU  底层诸多库函数
 */
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.AbstractModule;
import rescuecore2.misc.geometry.GeometryTools2D;
import rescuecore2.misc.geometry.Line2D;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.misc.geometry.Vector2D;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;
import java.util.stream.Collectors;

public class SEUGeomTool extends AbstractModule {

    public SEUGeomTool(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
    }

    /*
        判断目标点是否被路障覆盖
    */
    public boolean isInside(double x, double y, Blockade blockade) {
        return blockade.getShape().contains(x, y);
    }

    /*
        判断目标点是否离路障边缘过近，range最好比智能体半径大（智能体半径好像是250还是500，civilian的半径比智能体稍小）
    */
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

    /*
        判断目标Human是否被特定路障困住或挡住
    */
    public boolean isHumanStucked(Human human, Blockade blockade) {
        int x = human.getX();
        int y = human.getY();
        return this.isInside(x, y, blockade) || this.isNearBlockade(x, y, blockade, 600);
    }

    /*
        判断目标Human是否困在目标Area中或被目标Area中的路障挡住
    */
    public boolean isHumanStucked(Human human, Area area) {
        if (!area.isBlockadesDefined() || area.getBlockades().isEmpty()) return false;
        for (Blockade blockade : this.worldInfo.getBlockades(area)) {
            if (this.isHumanStucked(human, blockade)) return true;
        }
        return false;
    }

    /*
        判断目标Human是否被路障困住
    */
    public boolean isHumanStucked(Human human) {
        StandardEntity entity = this.worldInfo.getPosition(human);
        if (entity instanceof AmbulanceTeam) entity = this.worldInfo.getPosition(entity.getID());
        if (!(entity instanceof Area)) return false;
        return this.isHumanStucked(human, (Area) entity);
    }

    /*
        得到一个Area的可通过的Edge（不考虑路障的情况下）
    */
    public Set<Edge> getPassableEdge(Area area) {
        return area.getEdges().stream().filter(Edge::isPassable).collect(Collectors.toSet());
    }

    /*
        得到一个Building的所有入口
    */
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

    public Set<EntityID> getAllEntrancesOfBuilding(EntityID buildingID) {
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
        得到两个向量的夹角（弧度）
    */
    public double getAngle(Vector2D v1, Vector2D v2) {
        double flag = (v1.getX() * v2.getY()) - (v1.getY() * v2.getX());
        double angle = Math.acos(((v1.getX() * v2.getX()) + (v1.getY() * v2.getY())) / (v1.getLength() * v2.getLength()));
        if(flag > 0) return angle;
        if(flag < 0) return -1 * angle;
        return 0.0D;
    }

    /*
        判断目标点是否在顶点数组围成的区域内（Sample给出的方法，不知道和java的Shape的contains相比哪个好）
    */
    public boolean isInside(double pX, double pY, int[] apex) {
        Point2D p = new Point2D(pX, pY);
        Vector2D v1 = (new Point2D(apex[apex.length - 2], apex[apex.length - 1])).minus(p);
        Vector2D v2 = (new Point2D(apex[0], apex[1])).minus(p);
        double theta = this.getAngle(v1, v2);

        for(int i = 0; i < apex.length - 2; i += 2) {
            v1 = (new Point2D(apex[i], apex[i + 1])).minus(p);
            v2 = (new Point2D(apex[i + 2], apex[i + 3])).minus(p);
            theta += this.getAngle(v1, v2);
        }
        return Math.round(Math.abs((theta / 2) / Math.PI)) >= 1;
    }

    /*
        判断线段是否与Blockade相交
    */
    public boolean intersect(double x1, double y1, double x2, double y2, Blockade blockade) {
        List<Line2D> lines = GeometryTools2D.pointsToLines(GeometryTools2D.vertexArrayToPoints(blockade.getApexes()), true);
        for(Line2D line : lines) {
            Point2D start = line.getOrigin();
            Point2D end = line.getEndPoint();
            double startX = start.getX();
            double startY = start.getY();
            double endX = end.getX();
            double endY = end.getY();
            if(java.awt.geom.Line2D.linesIntersect(x1, y1, x2, y2, startX, startY, endX, endY)) return true;
        }
        return false;
    }

    /*
        判断线段是否与Area相交
    */
    public boolean intersect(double x1, double y1, double x2, double y2, Area area) {
        for (Edge edge : area.getEdges()) {
            double startX = edge.getStartX();
            double startY = edge.getStartY();
            double endX = edge.getEndX();
            double endY = edge.getEndY();
            if (java.awt.geom.Line2D.linesIntersect(x1, y1, x2, y2, startX, startY, endX, endY)) return true;
        }
        return false;
    }

    /*
        从ids集合中找到离reference最近的entity的ID
    */
    public EntityID getClosestEntityID(Collection<EntityID> ids, EntityID reference) {
        double minDistance = Double.MAX_VALUE;
        EntityID closestID = null;
        for (EntityID id : ids) {
            double distance = this.worldInfo.getDistance(id, reference);
            if (distance < minDistance) {
                minDistance = distance;
                closestID = id;
            }
        }
        return closestID;
    }

    /*
        从entities中找到离reference最近的entity，以StandardEntity的形式返回
    */
    public StandardEntity getClosestEntity(Collection<? extends StandardEntity> entities, StandardEntity reference) {
        double minDistance = Double.MAX_VALUE;
        StandardEntity closest = null;
        for (StandardEntity entity : entities) {
            double distance = this.worldInfo.getDistance(entity, reference);
            if (distance < minDistance) {
                minDistance = distance;
                closest = entity;
            }
        }
        return closest;
    }






    /*
        得到相邻的房屋（可能不直接相连）
    */
    public List<EntityID> getNeighborBuildings(Building building)
    {
        List<EntityID> BuildingIDs=new ArrayList<>();
        for(EntityID entityid:this.calcNeighbors(building))
        {
            if(this.worldInfo.getEntity(entityid) instanceof Building)
            {
                BuildingIDs.add(entityid);
            }
        }
        return BuildingIDs;
    }

    /*
        得到相邻的路（可能不直接相连）××××××××××××××××××××××××××××××××
    */
    public List<EntityID> getNeighborRoads(Building building)
    {
        List<EntityID> RoadIDs=new ArrayList<>();
        for(EntityID entityid:this.calcNeighbors(building))
        {
            if(this.worldInfo.getEntity(entityid) instanceof Road)
            {
                RoadIDs.add(entityid);
            }
        }
        return RoadIDs;
    }

    /*
        得到邻居(可能不直接相连)
    */
    public List<EntityID> calcNeighbors(Building building) {
        List<EntityID> neighborIDs = new ArrayList<>();
        int ox = building.getX();
        int oy = building.getY();
        for(EntityID entityid : this.worldInfo.getObjectIDsInRange(building.getID(), 30000))
        {
            if(!entityid.equals(building.getID()))
            {
                boolean ifneighbor = true;
                if(this.worldInfo.getEntity(entityid) instanceof Building)
                {
                    Building targetBuilding = (Building) this.worldInfo.getEntity(entityid);
                    int bx = targetBuilding.getX();
                    int by = targetBuilding.getY();
                    for(EntityID entityid2 : this.worldInfo.getObjectIDsInRange(targetBuilding.getID(), 30000))
                    {
                        if(this.worldInfo.getEntity(entityid2) instanceof Area && !entityid2.equals(entityid) && !entityid2.equals(building.getID()))
                        {
                            Area area = (Area) this.worldInfo.getEntity(entityid2);
                            if(this.intersect(ox, oy, bx, by, area))
                            {
                                ifneighbor = false;
                                break;
                            }
                        }
                    }
                }
                else if(this.worldInfo.getEntity(entityid) instanceof Road)
                {
                    Road targetRoad = (Road) this.worldInfo.getEntity(entityid);
                    int rx = targetRoad.getX();
                    int ry = targetRoad.getY();
                    for(EntityID entityid2 : this.worldInfo.getObjectIDsInRange(targetRoad.getID(), 30000))
                    {
                        if(this.worldInfo.getEntity(entityid2) instanceof Area && !entityid2.equals(entityid) && !entityid2.equals(building.getID()))
                        {
                            Area area = (Area) this.worldInfo.getEntity(entityid2);
                            if(this.intersect(ox, oy, rx, ry, area))
                            {
                                ifneighbor = false;
                                break;
                            }
                        }
                    }
                }
                if(ifneighbor == true) neighborIDs.add(entityid);
            }
        }
        return neighborIDs;
    }



    public SEUGeomTool precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        if (this.getCountPrecompute() >= 2) {
            return this;
        }
        return this;
    }

    public SEUGeomTool resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) {
            return this;
        }
        return this;
    }

    public SEUGeomTool preparate() {
        super.preparate();
        if (this.getCountPreparate() >= 2) {
            return this;
        }
        return this;
    }

    public SEUGeomTool calc() {
        return this;
    }
}
