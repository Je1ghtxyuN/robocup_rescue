package sample_team.module.Action;

import adf.core.agent.action.Action;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.police.ActionClear;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.PathPlanning;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import rescuecore2.misc.geometry.GeometryTools2D;
import rescuecore2.misc.geometry.Line2D;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.PoliceForce;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

public class OptimizedExtActionClear extends ExtAction {

    private PathPlanning pathPlanning;
    private int clearDistance;
    private EntityID target;
    private Map<EntityID, Set<Blockade>> blockadeCache;
    private Map<EntityID, EntityID> lastClearedBlockade = new HashMap<>(); // 记录上次清理的障碍物

    public OptimizedExtActionClear(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
        ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.clearDistance = si.getClearRepairDistance();
        this.target = null;
        this.blockadeCache = new HashMap<>();

        this.pathPlanning = moduleManager.getModule(
            "OptimizedExtActionClear.PathPlanning",
            "adf.impl.module.algorithm.DijkstraPathPlanning");
    }

    @Override
    public ExtAction precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        this.pathPlanning.precompute(precomputeData);
        return this;
    }

    @Override
    public ExtAction resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        this.pathPlanning.resume(precomputeData);
        return this;
    }

    @Override
    public ExtAction preparate() {
        super.preparate();
        this.pathPlanning.preparate();
        return this;
    }

    @Override
    public ExtAction updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        this.pathPlanning.updateInfo(messageManager);
        this.blockadeCache.clear(); // 清除缓存以反映最新状态
        return this;
    }

    @Override
    public ExtAction setTarget(EntityID target) {
        this.target = null;
        StandardEntity entity = this.worldInfo.getEntity(target);
        if (entity == null) return this;
        if (entity instanceof Road) {
            this.target = target;
        } else if (entity.getStandardURN() == StandardEntityURN.BLOCKADE) {
            this.target = ((Blockade) entity).getPosition();
        } else if (entity instanceof Building) {
            this.target = target;
        }
        return this;
    }

    @Override
    public ExtAction calc() {
        this.result = null;
        PoliceForce police = (PoliceForce) this.agentInfo.me();
        if (this.target == null) return this;

        EntityID agentPos = police.getPosition();
        StandardEntity targetEntity = worldInfo.getEntity(target);
        if (targetEntity == null || !(targetEntity instanceof Area)) return this;

        // 根据位置执行不同策略
        if (agentPos.equals(target)) {
            this.result = getAreaClearAction(police, (Area) targetEntity);
        } else {
            this.result = planPathToTarget(police, agentPos, targetEntity);
        }
        return this;
    }

    // 核心优化方法：智能清理策略
    private Action getAreaClearAction(PoliceForce police, Area area) {
        if (area instanceof Building) return null;
        Road road = (Road) area;
        
        // 获取路障并缓存
        Collection<Blockade> blockades = getBlockadesForRoad(road);
        if (blockades.isEmpty()) return null;

        // 1. 寻找可清理的障碍物（在清理范围内）
        Blockade clearableBlockade = findClearableBlockade(police, blockades);
        if (clearableBlockade != null) {
            // 记录清理的障碍物
            lastClearedBlockade.put(police.getID(), clearableBlockade.getID());
            return new ActionClear(clearableBlockade);
        }

        // 2. 寻找最佳移动目标（障碍物上最近的点）
        Point2D bestMovePoint = findBestMovePoint(police, blockades);
        if (bestMovePoint != null) {
            // 如果上次尝试清理同一障碍物，直接清理
            EntityID lastBlockadeID = lastClearedBlockade.get(police.getID());
            if (lastBlockadeID != null) {
                Blockade lastBlockade = (Blockade) worldInfo.getEntity(lastBlockadeID);
                if (lastBlockade != null && blockades.contains(lastBlockade)) {
                    return new ActionClear(lastBlockade);
                }
            }
            
            // 记录目标障碍物
            Blockade targetBlockade = findBlockadeContainingPoint(blockades, bestMovePoint);
            if (targetBlockade != null) {
                lastClearedBlockade.put(police.getID(), targetBlockade.getID());
            }
            
            // 移动到最佳点
            return new ActionMove(
                Collections.singletonList(road.getID()), 
                (int) bestMovePoint.getX(), 
                (int) bestMovePoint.getY()
            );
        }
        
        return null;
    }

    // 寻找在清理范围内的障碍物
    private Blockade findClearableBlockade(PoliceForce police, Collection<Blockade> blockades) {
        for (Blockade blockade : blockades) {
            // 检查警察是否在障碍物附近
            if (isNearBlockade(police, blockade)) {
                return blockade;
            }
        }
        return null;
    }

    // 检查警察是否在障碍物附近（考虑清理距离）
    private boolean isNearBlockade(PoliceForce police, Blockade blockade) {
        // 计算警察到障碍物的距离
        double distance = getDistance(police.getX(), police.getY(), 
                                     blockade.getX(), blockade.getY());
        
        // 如果距离小于清理距离，警察在范围内
        if (distance < clearDistance) {
            return true;
        }
        
        // 检查警察是否在障碍物边界附近
        List<Line2D> lines = GeometryTools2D.pointsToLines(
            GeometryTools2D.vertexArrayToPoints(blockade.getApexes()), true);
        
        for (Line2D line : lines) {
            Point2D closestPoint = GeometryTools2D.getClosestPointOnSegment(line, 
                new Point2D(police.getX(), police.getY()));
            double closestDistance = getDistance(police.getX(), police.getY(), 
                                                closestPoint.getX(), closestPoint.getY());
            if (closestDistance < clearDistance) {
                return true;
            }
        }
        
        return false;
    }

    // 寻找最佳移动点（障碍物上最近的点）
    private Point2D findBestMovePoint(PoliceForce police, Collection<Blockade> blockades) {
        Point2D bestPoint = null;
        double minDistance = Double.MAX_VALUE;
        
        for (Blockade blockade : blockades) {
            Point2D closestPoint = findClosestPointOnBlockade(police, blockade);
            if (closestPoint != null) {
                double distance = getDistance(police.getX(), police.getY(), 
                                             closestPoint.getX(), closestPoint.getY());
                if (distance < minDistance) {
                    minDistance = distance;
                    bestPoint = closestPoint;
                }
            }
        }
        
        return bestPoint;
    }

    // 找到障碍物上离警察最近的点
    private Point2D findClosestPointOnBlockade(PoliceForce police, Blockade blockade) {
        int[] apexes = blockade.getApexes();
        if (apexes.length < 2) return null;
        
        double minDistance = Double.MAX_VALUE;
        Point2D closestPoint = null;
        
        // 检查所有顶点
        for (int i = 0; i < apexes.length; i += 2) {
            double distance = getDistance(police.getX(), police.getY(), 
                                         apexes[i], apexes[i + 1]);
            if (distance < minDistance) {
                minDistance = distance;
                closestPoint = new Point2D(apexes[i], apexes[i + 1]);
            }
        }
        
        // 检查线段上的最近点
        List<Line2D> lines = GeometryTools2D.pointsToLines(
            GeometryTools2D.vertexArrayToPoints(blockade.getApexes()), true);
        
        for (Line2D line : lines) {
            Point2D point = GeometryTools2D.getClosestPointOnSegment(line, 
                new Point2D(police.getX(), police.getY()));
            double distance = getDistance(police.getX(), police.getY(), 
                                         point.getX(), point.getY());
            if (distance < minDistance) {
                minDistance = distance;
                closestPoint = point;
            }
        }
        
        return closestPoint;
    }

    // 查找包含给定点的障碍物
    private Blockade findBlockadeContainingPoint(Collection<Blockade> blockades, Point2D point) {
        for (Blockade blockade : blockades) {
            if (isPointInsideBlockade(point, blockade)) {
                return blockade;
            }
        }
        return null;
    }

    // 检查点是否在障碍物内部
    private boolean isPointInsideBlockade(Point2D point, Blockade blockade) {
        int[] apexes = blockade.getApexes();
        if (apexes.length < 6) return false; // 需要至少3个点形成多边形
        
        // 使用射线法判断点是否在多边形内部
        int count = 0;
        int n = apexes.length / 2;
        
        for (int i = 0; i < n; i++) {
            int x1 = apexes[i * 2];
            int y1 = apexes[i * 2 + 1];
            int x2 = apexes[((i + 1) % n) * 2];
            int y2 = apexes[((i + 1) % n) * 2 + 1];
            
            if (((y1 > point.getY()) != (y2 > point.getY())) &&
                (point.getX() < (x2 - x1) * (point.getY() - y1) / (y2 - y1) + x1)) {
                count++;
            }
        }
        
        return count % 2 == 1;
    }

    // 获取道路上的路障（带缓存）
    private Collection<Blockade> getBlockadesForRoad(Road road) {
        if (!blockadeCache.containsKey(road.getID())) {
            Collection<Blockade> blockades = worldInfo.getBlockades(road).stream()
                .filter(Blockade::isApexesDefined)
                .collect(Collectors.toList());
            blockadeCache.put(road.getID(), Set.copyOf(blockades));
        }
        return blockadeCache.get(road.getID());
    }

    // 路径规划到目标区域
    private Action planPathToTarget(PoliceForce police, EntityID agentPos, StandardEntity targetEntity) {
        List<EntityID> path = pathPlanning.getResult(agentPos, target);
        if (path == null || path.isEmpty()) return null;
        
        // 直接移动到目标区域
        return new ActionMove(path);
    }
    
    // 计算两点距离
    private double getDistance(double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        return Math.sqrt(dx * dx + dy * dy);
    }
}