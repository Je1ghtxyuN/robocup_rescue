package SEU.extaction;

import adf.core.agent.action.Action;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.common.ActionRest;
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
//import com.google.common.collect.Lists;
import java.util.*;
import java.util.stream.Collectors;

import org.apache.log4j.Logger;
import rescuecore2.config.NoSuchConfigOptionException;
import rescuecore2.misc.geometry.GeometryTools2D;
import rescuecore2.misc.geometry.Line2D;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.misc.geometry.Vector2D;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Edge;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.PoliceForce;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

import adf.core.debug.DefaultLogger;

public class DefaultExtActionClear extends ExtAction {

  private PathPlanning pathPlanning;

  // clear parameter
  private int clearDistance;
  private int clearRate;
  private int clearRad;
  private double clearDistanceScale;

  private int forcedMove;
  private int thresholdRest;
  private int kernelTime;

  private EntityID target;
  private Map<EntityID, Set<Point2D>> movePointCache;

  private Logger logger;

  private EntityID lastPositionID;
  private double lastPositionX;
  private double lastPositionY;

  private int stoptime;
  private int stopTimeThreshold;

  public DefaultExtActionClear(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);

    
    this.clearDistance = si.getClearRepairDistance();
    this.clearRate = si.getClearRepairRate();
    this.clearRad = si.getClearRepairRad();
    this.clearDistanceScale=0.5;

    this.lastPositionID = null;
    this.lastPositionX = -10000;
    this.lastPositionY = -10000;
    this.stoptime = 0;
    this.stopTimeThreshold = 2;


    this.forcedMove = developData
        .getInteger("adf.impl.extaction.DefaultExtActionClear.forcedMove", 3);
    this.thresholdRest = developData
        .getInteger("adf.impl.extaction.DefaultExtActionClear.rest", 100);

    this.target = null;
    this.movePointCache = new HashMap<>();


    logger = DefaultLogger.getLogger(agentInfo.me());

    switch (si.getMode()) {
      case PRECOMPUTATION_PHASE:
      case PRECOMPUTED:
      case NON_PRECOMPUTE:
        this.pathPlanning = moduleManager.getModule(
            "DefaultExtActionClear.PathPlanning",
            "adf.impl.module.algorithm.DijkstraPathPlanning");
        break;
    }


  }


  @Override
  public ExtAction precompute(PrecomputeData precomputeData) {
    super.precompute(precomputeData);
    if (this.getCountPrecompute() >= 2) {
      return this;
    }
    this.pathPlanning.precompute(precomputeData);
    try {
      this.kernelTime = this.scenarioInfo.getKernelTimesteps();
    } catch (NoSuchConfigOptionException e) {
      this.kernelTime = -1;
    }
    return this;
  }


  @Override
  public ExtAction resume(PrecomputeData precomputeData) {
    super.resume(precomputeData);
    if (this.getCountResume() >= 2) {
      return this;
    }
    this.pathPlanning.resume(precomputeData);
    try {
      this.kernelTime = this.scenarioInfo.getKernelTimesteps();
    } catch (NoSuchConfigOptionException e) {
      this.kernelTime = -1;
    }
    return this;
  }


  @Override
  public ExtAction preparate() {
    super.preparate();
    if (this.getCountPreparate() >= 2) {
      return this;
    }
    this.pathPlanning.preparate();
    try {
      this.kernelTime = this.scenarioInfo.getKernelTimesteps();
    } catch (NoSuchConfigOptionException e) {
      this.kernelTime = -1;
    }
    return this;
  }


  @Override
  public ExtAction updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
    if (this.getCountUpdateInfo() >= 2) {
      return this;
    }
    this.pathPlanning.updateInfo(messageManager);
    return this;
  }


  @Override
  public ExtAction setTarget(EntityID target) {
    this.target = null;
    StandardEntity entity = this.worldInfo.getEntity(target);
    if (entity != null) {
      if (entity instanceof Road) {
        this.target = target;
      } else if (entity.getStandardURN().equals(StandardEntityURN.BLOCKADE)) {
        this.target = ((Blockade) entity).getPosition();
      } else if (entity instanceof Building) {
        this.target = target;
      }
    }
    return this;
  }


  @Override
  public ExtAction calc() {

    logger.debug("target:"+this.target.toString());
    logger.debug("stop time"+this.stoptime);


    this.result = null;
    PoliceForce policeForce = (PoliceForce) this.agentInfo.me();


    if (this.target == null) {

      logger.debug("target_null");
      refreshInfo();
      return this;
      
    }

    EntityID agentPosition = policeForce.getPosition();
    StandardEntity targetEntity = this.worldInfo.getEntity(this.target);
    StandardEntity positionEntity = Objects.requireNonNull(this.worldInfo.getEntity(agentPosition));

    if (targetEntity == null || !(targetEntity instanceof Area)) {
      logger.debug("no Area");
      refreshInfo();
      return this;
    }

    if (positionEntity instanceof Road) {
      // stuck action
      this.result = getStuckAction(policeForce,(Road) positionEntity);
      if (this.result != null) {
        logger.debug("stuck action");
        refreshInfo();
        return this;
      }
    }

    // stopped action (stopped too long time)
    this.result = this.getStoppedAction(policeForce, (Area) positionEntity);
    if (this.result != null) {
      logger.debug("stopped action");
      refreshInfo();
      return this;
    }


    if (positionEntity instanceof Road) {
      // rescue action
      this.result = this.getRescueAction(policeForce, (Road) positionEntity);
      if (this.result != null) {
        logger.debug("rescue action");
        refreshInfo();
        return this;
      }
    }

    // area clear action
    if (agentPosition.equals(this.target)) {
      this.result = this.getAreaClearAction(policeForce, targetEntity);
      if (this.result != null) {
        logger.debug("area clear action");
        refreshInfo();
        return this;
      }
    }

    // blocked action
    this.result = this.getBlockedAction(policeForce, (Area) positionEntity);
    if (this.result != null) {
      logger.debug("blocked action");
      refreshInfo();
      return this;
    }


    // move action
    List<EntityID> path = this.pathPlanning.getResult(agentPosition, this.target);
    if (this.result == null) {
      this.result = new ActionMove(path);
      logger.debug("move action");
      refreshInfo();
      return this;
    }

    this.result = randomMove();
    logger.debug("random move");
    return this;
  }

  private Action getStuckAction(PoliceForce police, Road road){

    if(this.agentInfo.getTime()>10){
      return null;
    }

    if (!road.isBlockadesDefined()) {
      return null;
    }

    Collection<Blockade> blockades = this.worldInfo.getBlockades(road).stream()
            .filter(Blockade::isApexesDefined).collect(Collectors.toSet());

    int policeX = police.getX();
    int policeY = police.getY();
    int[] apexs;

    for(Blockade blockade:blockades){
      apexs = blockade.getApexes();
      if(isInside(policeX,policeY,apexs)){
        return getBlockadeClearAction(blockade);
      }
    }

    return null;

  }

  private Action getStoppedAction(PoliceForce police, Area road){

    if(stoptime<this.stopTimeThreshold){
      return null;
    }

    Collection<Blockade> blockades = this.worldInfo.getBlockades(road).stream()
            .filter(Blockade::isApexesDefined).collect(Collectors.toSet());

    if(!blockades.isEmpty()){
      double minDistance = Double.MAX_VALUE;
      double distance;
      Blockade clearBlockade = null;
      for(Blockade blockade:blockades){
        distance = pointPolyDistance(new Point2D(police.getX(),police.getY()),blockade.getApexes());
        if (distance<minDistance){
          minDistance = distance;
          clearBlockade = blockade;
        }
      }

      if (clearBlockade!=null){

        if(pointPolyDistance(new Point2D(police.getX(),police.getY()),clearBlockade.getApexes())
                <this.clearDistance*this.clearDistanceScale){
          return getBlockadeClearAction(clearBlockade);
        }else {
          List<EntityID> path = new ArrayList<>();
          path.add(this.agentInfo.getPosition());
          return new ActionMove(path, clearBlockade.getX(), clearBlockade.getY());
        }

      }
    }

    blockades = getNeighborBlockade(road);
    if(!blockades.isEmpty()){
      double minDistance = Double.MAX_VALUE;
      double distance;
      Blockade clearBlockade = null;
      for(Blockade blockade:blockades){
        distance = pointPolyDistance(new Point2D(police.getX(),police.getY()),blockade.getApexes());
        if (distance<minDistance){
          minDistance = distance;
          clearBlockade = blockade;
        }
      }

      if (clearBlockade!=null){

        if(pointPolyDistance(new Point2D(police.getX(),police.getY()),clearBlockade.getApexes())
                <this.clearDistance*this.clearDistanceScale){
          return getBlockadeClearAction(clearBlockade);
        }else {
          List<EntityID> path = new ArrayList<>();
          path.add(this.agentInfo.getPosition());
          return new ActionMove(path, clearBlockade.getX(), clearBlockade.getY());
        }

      }
    }

    // no blockade , try random move
    return randomMove();
  }

  private Action getRescueAction(PoliceForce police, Road road) {

    if (!road.isBlockadesDefined()) {
      return null;
    }

    Collection<Blockade> blockades = this.worldInfo.getBlockades(road).stream()
            .filter(Blockade::isApexesDefined).collect(Collectors.toSet());
    blockades.addAll(getNeighborBlockade(road));

    // 消防员,救护员
    Collection<StandardEntity> agents = this.worldInfo.getEntitiesOfType(
            StandardEntityURN.AMBULANCE_TEAM, StandardEntityURN.FIRE_BRIGADE);


    // 路上跑的人和建筑里被埋的人
    Collection<StandardEntity> agents_civilian = this.worldInfo.getEntitiesOfType(StandardEntityURN.CIVILIAN);
    for(StandardEntity entity:agents_civilian){

      Human human = (Human) entity;
      if(human.isBuriednessDefined()&&human.getBuriedness()>0&&human.isHPDefined()&&human.getHP()>0){
        agents.add(entity);
      }

      StandardEntity position = this.worldInfo.getPosition(human);
      if(position instanceof Road){
        agents.add(entity);
      }

    }

    double policeX = police.getX();
    double policeY = police.getY();
    Action moveAction = null;

    boolean needRescue;

    for (StandardEntity entity : agents) {

      Human human = (Human) entity;

      // 是否为当前道路目标
      if (!human.isPositionDefined() || human.getPosition().getValue() != road.getID().getValue()) {
        continue;
      }

      double humanX = human.getX();
      double humanY = human.getY();

      for (Blockade blockade : blockades) {

        needRescue = false;

        // 是否被困
        if (this.isInside(humanX, humanY, blockade.getApexes())) {
          needRescue = true;
        }

        // 是否被挡
        if(this.pointPolyDistance(new Point2D(humanX,humanY),blockade.getApexes())<1500){
          if(human.getPositionHistory() != null){
            int historyX = human.getPositionHistory()[human.getPositionHistory().length-2];
            int historyY = human.getPositionHistory()[human.getPositionHistory().length-1];
            if(getDistance(humanX,humanY,historyX,historyY)<1500){
              needRescue = true;
            }
          }
        }


        if(!needRescue){
          continue;
        }

        double distance_human = this.getDistance(policeX, policeY, humanX, humanY);
        double distance_blockade = pointPolyDistance(new Point2D(policeX,policeY),blockade.getApexes());
        if(distance_human>this.clearDistanceScale*this.clearDistance
                &&distance_blockade>this.clearDistance*this.clearDistanceScale){

          List<EntityID> list = new ArrayList<>();
          list.add(road.getID());
          moveAction = new ActionMove(list, (int) humanX, (int) humanY);

        }
        else {
          return getBlockadeClearAction(blockade);
        }
      }
    }

    return moveAction;

  }

  private Action getAreaClearAction(PoliceForce police, StandardEntity targetEntity) {


    if (targetEntity instanceof Building) {
      return null;
    }

    Road road = (Road) targetEntity;
    if (!road.isBlockadesDefined() || road.getBlockades().isEmpty()) {
      return null;
    }

    Collection<Blockade> blockades = this.worldInfo.getBlockades(road).stream()
        .filter(Blockade::isApexesDefined).collect(Collectors.toSet());

    Blockade clearBlockade = null;
    double minPointDistance = Double.MAX_VALUE;

    for (Blockade blockade : blockades) {
      int[] apexes = blockade.getApexes();
      double distance = pointPolyDistance(new Point2D(police.getX(),police.getY()),apexes);
      if (distance < minPointDistance) {
        clearBlockade = blockade;
        minPointDistance = distance;
      }
    }

    if (clearBlockade != null) {

      logger.debug("clearBlockade: "+clearBlockade.getID().toString());

      if (minPointDistance < this.clearDistance*this.clearDistanceScale) {
        return getBlockadeClearAction(clearBlockade);
      }

      List<EntityID> list = new ArrayList<>();
      list.add(police.getPosition());
      list.add(clearBlockade.getID());
      return new ActionMove(list,clearBlockade.getX(),clearBlockade.getY());

    }

    return null;
  }

  private Action getBlockedAction(PoliceForce policeForce,Area road){


    Area nextPosition = this.getNextArea();
    logger.debug("nextPosition"+nextPosition.toString());

    double nextX = 0;
    double nextY = 0;

    nextX = nextPosition.getX();
    nextY = nextPosition.getY();

    Vector2D vector = new Vector2D(nextX-policeForce.getX(),nextY-policeForce.getY());
    vector = vector.normalised().scale(this.clearDistance*this.clearDistanceScale);
    nextX = policeForce.getX()+vector.getX();
    nextY = policeForce.getY()+vector.getY();

    Collection<Blockade> blockades = this.worldInfo.getBlockades(road).stream()
            .filter(Blockade::isApexesDefined).collect(Collectors.toSet());

    for(Blockade blockade:blockades){
      if(intersect(policeForce.getX(),policeForce.getY(),nextX,nextY,blockade)){
        return getBlockadeClearAction(blockade);
      }
    }

    blockades = getNeighborBlockade(road);
    for(Blockade blockade:blockades){
      if(intersect(policeForce.getX(),policeForce.getY(),nextX,nextY,blockade)){
        return getBlockadeClearAction(blockade);
      }
    }

    return null;

  }

  private ActionMove randomMove(){

    EntityID position = this.agentInfo.getPosition();
    Collection<StandardEntity> inRangeEntity= this.worldInfo.getObjectsInRange(this.agentInfo.getID(),100000);
    Object[] inRangeEntityArray = inRangeEntity.toArray();

    Random random = new Random();
    int index;
    StandardEntity entity = null;

    int times=0;

    if(inRangeEntityArray.length>0){
      while (true){

        index = random.nextInt(inRangeEntityArray.length);
        entity = (StandardEntity) inRangeEntityArray[index];

        if (!(entity instanceof Area)){
          continue;
        }
        if (entity.getID()==position){
          continue;
        }

        times++;
        if(times>10){
          break;
        }

        break;

      }

    }

    if (entity != null) {
      List<EntityID> path = pathPlanning.getResult(position,entity.getID());
      return new ActionMove(path);
    }else {
      return null;
    }

  }

  private double getEdgeLength(Edge edge){

    return getDistance(edge.getStartX(),edge.getStartY(),edge.getEndX(),edge.getEndY());

  }

  private Area getNextArea(){

    List<EntityID> path = this.pathPlanning.getResult(this.agentInfo.getPosition(), this.target);

    if(path.size()>=2){
      return (Area) this.worldInfo.getEntity(path.get(1));

    }
    return (Area) this.worldInfo.getEntity(path.get(0));

  }

  private Collection<Blockade> getNeighborBlockade(Area road){


    Collection<Blockade> neighborBlockade = new ArrayList<>();
    for(EntityID neighborAreaID:road.getNeighbours()){

      Area area = (Area) this.worldInfo.getEntity(neighborAreaID);
      Collection<Blockade> blockades = null;
      if (area != null) {
        blockades = this.worldInfo.getBlockades(area).stream()
                .filter(Blockade::isApexesDefined).collect(Collectors.toSet());
      }
      if (blockades != null) {
        neighborBlockade.addAll(blockades);
      }


    }

    return neighborBlockade;
  }

  private void refreshInfo(){

    if(getDistance(this.lastPositionX,this.lastPositionY,this.agentInfo.getX(),this.agentInfo.getY())<300){
      stoptime ++;
    }

    if(getDistance(this.lastPositionX,this.lastPositionY,this.agentInfo.getX(),this.agentInfo.getY())>3000){
      stoptime=0;
    }

    if(this.result.getClass()==ActionClear.class){
      stoptime = 0;
    }

    this.lastPositionX = this.agentInfo.getX();
    this.lastPositionY = this.agentInfo.getY();
//    this.lastPositionID = this.agentInfo.getPosition();

  }

  private boolean isBuildingEntrance(Road road){

    for (EntityID neighbourID : road.getNeighbours()) {
      StandardEntity neighbour = this.worldInfo.getEntity(neighbourID);
      if (neighbour instanceof Building ) {
        return true;
      }
    }

    return false;
  }

  private double pointSegmentDistance(Point2D point,Line2D line){

    Point2D closestPoint = GeometryTools2D.getClosestPointOnSegment(line,point);
    return GeometryTools2D.getDistance(point,closestPoint);

  }

  private double pointPolyDistance(Point2D point,int []apex){
    List<Line2D> lines = GeometryTools2D.pointsToLines(GeometryTools2D.vertexArrayToPoints(apex), true);

    double distance = Double.MAX_VALUE;
    double tempDistacne;

    for(Line2D line:lines){
      tempDistacne = pointSegmentDistance(point,line);
      if(tempDistacne<distance){

        distance=tempDistacne;

      }
    }

    return distance;

  }

  private Action getBlockadeClearAction(Blockade blockade) {

    if (blockade.getRepairCost() < this.clearRate && blockade.getRepairCost() > this.clearRate * 3 /4 ) {
      return new ActionClear(blockade);
    }

    if (blockade.getRepairCost() > this.clearRate *3) {
      return new ActionClear(blockade);
    }

    EntityID positionID = blockade.getPosition();
    StandardEntity entity = this.worldInfo.getEntity(positionID);

    // building entrance
    if (entity != null && isBuildingEntrance((Road) entity)) {
      return new ActionClear(blockade);
    }


    // other , try to maximum clear area
    int centerX = 0;
    int centerY = 0;
    int[] apexes = blockade.getApexes();

    for (int i = 0; i < apexes.length; i = i + 2) {
      centerX += apexes[i];
      centerY += apexes[i + 1];
    }
    centerX = 2 * centerX / apexes.length;
    centerY = 2 * centerY / apexes.length;


    return scaleClearAction(centerX, centerY);


  }
  

  private boolean equalsPoint(double p1X, double p1Y, double p2X, double p2Y) {
    return this.equalsPoint(p1X, p1Y, p2X, p2Y, 1000.0D);
  }


  private boolean equalsPoint(double p1X, double p1Y, double p2X, double p2Y, double range) {
    return (p2X - range < p1X && p1X < p2X + range)
        && (p2Y - range < p1Y && p1Y < p2Y + range);
  }


  private boolean isInside(double pX, double pY, int[] apex) {
    Point2D p = new Point2D(pX, pY);
    Vector2D v1 = (new Point2D(apex[apex.length - 2], apex[apex.length - 1]))
        .minus(p);
    Vector2D v2 = (new Point2D(apex[0], apex[1])).minus(p);
    double theta = this.getAngle(v1, v2);

    for (int i = 0; i < apex.length - 2; i += 2) {
      v1 = (new Point2D(apex[i], apex[i + 1])).minus(p);
      v2 = (new Point2D(apex[i + 2], apex[i + 3])).minus(p);
      theta += this.getAngle(v1, v2);
    }
    return Math.round(Math.abs((theta / 2) / Math.PI)) >= 1;
  }


  private boolean intersect(double agentX, double agentY, double pointX,
      double pointY, Blockade blockade) {
    List<Line2D> lines = GeometryTools2D.pointsToLines(
        GeometryTools2D.vertexArrayToPoints(blockade.getApexes()), true);
    for (Line2D line : lines) {
      Point2D start = line.getOrigin();
      Point2D end = line.getEndPoint();
      double startX = start.getX();
      double startY = start.getY();
      double endX = end.getX();
      double endY = end.getY();
      if (java.awt.geom.Line2D.linesIntersect(agentX, agentY, pointX, pointY,
          startX, startY, endX, endY)) {
        return true;
      }
    }
    return false;
  }

  private double getDistance(double fromX, double fromY, double toX, double toY) {
    double dx = toX - fromX;
    double dy = toY - fromY;
    return Math.hypot(dx, dy);
  }

  private double getAngle(Vector2D v1, Vector2D v2) {
    double flag = (v1.getX() * v2.getY()) - (v1.getY() * v2.getX());
    double angle = Math.acos(((v1.getX() * v2.getX()) + (v1.getY() * v2.getY()))
        / (v1.getLength() * v2.getLength()));
    if (flag > 0) {
      return angle;
    }
    if (flag < 0) {
      return -1 * angle;
    }
    return 0.0D;
  }

  private Vector2D getVector(double fromX, double fromY, double toX, double toY) {
    return (new Point2D(toX, toY)).minus(new Point2D(fromX, fromY));
  }

  private Vector2D scaleClear(Vector2D vector) {
    return vector.normalised().scale(this.clearDistance);
  }

  private ActionClear scaleClearAction(int targetX,int targetY){

    double positionX = this.agentInfo.getX();
    double positionY = this.agentInfo.getY();

    Vector2D vector = getVector(positionX,positionY,targetX,targetY);
    vector = scaleClear(vector);

    return new ActionClear((int) (positionX+vector.getX()), (int) (positionY+vector.getY()));

  }

  private Set<Point2D> getMovePoints(Road road) {
    Set<Point2D> points = this.movePointCache.get(road.getID());
    if (points == null) {
      points = new HashSet<>();
      int[] apex = road.getApexList();
      for (int i = 0; i < apex.length; i += 2) {
        for (int j = i + 2; j < apex.length; j += 2) {
          double midX = (apex[i] + apex[j]) / 2;
          double midY = (apex[i + 1] + apex[j + 1]) / 2;
          if (this.isInside(midX, midY, apex)) {
            points.add(new Point2D(midX, midY));
          }
        }
      }
      for (Edge edge : road.getEdges()) {
        double midX = (edge.getStartX() + edge.getEndX()) / 2;
        double midY = (edge.getStartY() + edge.getEndY()) / 2;
        points.remove(new Point2D(midX, midY));
      }
      this.movePointCache.put(road.getID(), points);
    }
    return points;
  }

  private boolean needRest(Human agent) {
    int hp = agent.getHP();
    int damage = agent.getDamage();
    if (damage == 0 || hp == 0) {
      return false;
    }
    int activeTime = (hp / damage) + ((hp % damage) != 0 ? 1 : 0);
    if (this.kernelTime == -1) {
      try {
        this.kernelTime = this.scenarioInfo.getKernelTimesteps();
      } catch (NoSuchConfigOptionException e) {
        this.kernelTime = -1;
      }
    }
    return damage >= this.thresholdRest
        || (activeTime + this.agentInfo.getTime()) < this.kernelTime;
  }

  private Action calcRest(Human human, PathPlanning pathPlanning, Collection<EntityID> targets) {
    EntityID position = human.getPosition();
    Collection<EntityID> refuges = this.worldInfo
        .getEntityIDsOfType(StandardEntityURN.REFUGE);
    int currentSize = refuges.size();
    if (refuges.contains(position)) {
      return new ActionRest();
    }
    List<EntityID> firstResult = null;
    while (refuges.size() > 0) {
      pathPlanning.setFrom(position);
      pathPlanning.setDestination(refuges);
      List<EntityID> path = pathPlanning.calc().getResult();
      if (path != null && path.size() > 0) {
        if (firstResult == null) {
          firstResult = new ArrayList<>(path);
          if (targets == null || targets.isEmpty()) {
            break;
          }
        }
        EntityID refugeID = path.get(path.size() - 1);
        pathPlanning.setFrom(refugeID);
        pathPlanning.setDestination(targets);
        List<EntityID> fromRefugeToTarget = pathPlanning.calc().getResult();
        if (fromRefugeToTarget != null && fromRefugeToTarget.size() > 0) {
          return new ActionMove(path);
        }
        refuges.remove(refugeID);
        // remove failed
        if (currentSize == refuges.size()) {
          break;
        }
        currentSize = refuges.size();
      } else {
        break;
      }
    }
    return firstResult != null ? new ActionMove(firstResult) : null;
  }



}

