package SEU.extaction;

import static rescuecore2.standard.entities.StandardEntityURN.BLOCKADE;
import adf.core.agent.action.Action;
import adf.core.agent.action.ambulance.ActionRescue;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.PathPlanning;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import org.apache.log4j.Logger;
import rescuecore2.config.NoSuchConfigOptionException;
import rescuecore2.misc.geometry.GeometryTools2D;
import rescuecore2.misc.geometry.Line2D;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.misc.geometry.Vector2D;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import adf.core.debug.DefaultLogger;

public class DefaultExtActionFireRescue extends ExtAction {

  private Logger logger;

  private PathPlanning pathPlanning;

  private int thresholdRest;
  private int kernelTime;

  private EntityID target;

  private int stopTime;
  private int stopTimeThreshold;
  private double lastPositionX;
  private double lastPositionY;

  private List<Point2D> detourPath;

  public DefaultExtActionFireRescue(AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo, ModuleManager moduleManager, DevelopData developData) {
    super(agentInfo, worldInfo, scenarioInfo, moduleManager, developData);
    this.target = null;
    this.thresholdRest = developData
        .getInteger("adf.impl.extaction.DefaultExtActionFireRescue.rest", 100);

    switch (scenarioInfo.getMode()) {
      case PRECOMPUTATION_PHASE:
      case PRECOMPUTED:
      case NON_PRECOMPUTE:
        this.pathPlanning = moduleManager.getModule(
            "DefaultExtActionFireRescue.PathPlanning",
            "adf.impl.module.algorithm.DijkstraPathPlanning");
        break;
    }


    this.stopTime = 0;
    this.stopTimeThreshold = 2;
    this.lastPositionX = -10000;
    this.lastPositionY = -10000;

    logger = DefaultLogger.getLogger(agentInfo.me());

    this.detourPath = new ArrayList<>();

  }


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
    if (target != null) {
      StandardEntity entity = this.worldInfo.getEntity(target);
      if (entity instanceof Human || entity instanceof Area) {
        this.target = target;
        return this;
      }
    }
    return this;
  }


  @Override
  public ExtAction calc() {
    this.result = null;
    FireBrigade agent = (FireBrigade) this.agentInfo.me();


    logger.debug("stop time: "+this.stopTime);

    // rest action
    if (this.needRest(agent)) {
      EntityID areaID = this.convertArea(this.target);
      ArrayList<EntityID> targets = new ArrayList<>();
      if (areaID != null) {
        targets.add(areaID);
      }
    }

    // // rescue here action
    // this.result = rescueHereAction();
    // if(this.result!=null){
    //   refreshInfo();
    //   return this;
    // }

    // stuck action
//    this.result = this.getStuckAction();
//    if(this.result!=null){
//      refreshInfo();
//      return this;
//    }

    // traditional rescue action
    if (this.target != null) {
      this.result = this.calcRescue(agent, this.pathPlanning, this.target);
    }

    refreshInfo();
    return this;
  }


  private Action rescueHereAction(){

    Area positionArea = this.agentInfo.getPositionArea();
    if(!(positionArea instanceof Building building)){
      return  null;
    }

    Collection<Human> buriedHumans = this.worldInfo.getBuriedHumans(building);

    if(buriedHumans.isEmpty()){
      return null;
    }

    Human rescueHuman = null;

    for(Human human:buriedHumans){
      if (human.isHPDefined() && human.getHP()>0) {
        if(rescueHuman==null){
          rescueHuman = human;
        }else if(rescueHuman.getDamage()<human.getDamage()){
          rescueHuman=human;
        }
      }
    }

    if(rescueHuman==null){
      return null;
    }else {
      return new ActionRescue(rescueHuman);
    }

  }

  private Action calcRescue(FireBrigade agent, PathPlanning pathPlanning, EntityID targetID) {

    StandardEntity targetEntity = this.worldInfo.getEntity(targetID);

    if (targetEntity == null) {
      return null;
    }

    EntityID agentPosition = agent.getPosition();

    if (targetEntity instanceof Human) {
      Human human = (Human) targetEntity;

      if (!human.isPositionDefined()) {
        return null;
      }
      if (human.isHPDefined() && human.getHP() == 0) {
        return null;
      }

      EntityID targetPosition = human.getPosition();
      if (agentPosition.getValue() == targetPosition.getValue()) {

        if (human.isBuriednessDefined() && human.getBuriedness() > 0&&human.getHP()>0) {
          return new ActionRescue(human);
        }

      } else {

        List<EntityID> path = pathPlanning.getResult(agentPosition, targetPosition);
        if (path != null && !path.isEmpty()) {
          return new ActionMove(path);
        }

      }

      return null;

    }

    if (targetEntity.getStandardURN() == BLOCKADE) {
      Blockade blockade = (Blockade) targetEntity;
      if (blockade.isPositionDefined()) {
        targetEntity = this.worldInfo.getEntity(blockade.getPosition());
      }
    }

    if (targetEntity instanceof Area) {
      List<EntityID> path = pathPlanning.getResult(agentPosition, targetEntity.getID());
      if (path != null && !path.isEmpty()) {
        return new ActionMove(path);
      }
    }

    return null;
  }

  private Action getStuckAction(){

    if(stopTime<stopTimeThreshold){
      return null;
    }
    stopTime = 0;


//    return randomMove();
    return null;

  }

  private void refreshInfo(){

    if(getDistance(this.lastPositionX,this.lastPositionY,this.agentInfo.getX(),this.agentInfo.getY())<1000){
      stopTime ++;
    }

    if(getDistance(this.lastPositionX,this.lastPositionY,this.agentInfo.getX(),this.agentInfo.getY())>3000){
      stopTime=0;
    }

    if(this.result!=null&&this.result.getClass()==ActionRescue.class){
      stopTime = 0;
    }

    this.lastPositionX = this.agentInfo.getX();
    this.lastPositionY = this.agentInfo.getY();

  }

  private Edge getNextEdge(EntityID positionID,Blockade blockade){

    List<EntityID> path = this.pathPlanning.getResult(positionID, this.target);

    if(path==null){
      return null;
    }

    if(path.size()<2){
      return null;
    }

    if(positionID==blockade.getPosition()){

      Area positionArea = this.agentInfo.getPositionArea();
      return positionArea.getEdgeTo(path.get(1));

    }

    if(blockade.getPosition()==path.get(1)&&path.size()>2){

      Area blockadeArea = (Area) this.worldInfo.getEntity(blockade.getPosition());
      if (blockadeArea != null) {
        return blockadeArea.getEdgeTo(path.get(2));
      }

    }

    return null;

  }

  private List<Point2D> areaSampling(Area area){

    List<Edge>edges = area.getEdges();
    int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,maxX=-10000,maxY=-10000;

    for(Edge edge:edges){

      if(edge.getStartX()>maxX) maxX = edge.getStartX();
      if(edge.getStartX()<minX) minX = edge.getStartX();

      if(edge.getEndX()>maxX) maxX = edge.getEndX();
      if(edge.getEndX()<minX) minX = edge.getEndX();

      if(edge.getStartY()>maxY) maxY = edge.getStartY();
      if(edge.getStartY()<minY) minY = edge.getStartY();

      if(edge.getEndY()>maxY) maxY = edge.getEndY();
      if(edge.getEndY()<minY) minY = edge.getEndY();
    }

    int[] apexes = area.getApexList();

    List<Point2D> samplePoint = new ArrayList<>();
    int sampleNum=8;

    int distanceX = maxX-minX;
    int distanceY = maxY-minY;
    int tempX,tempY;

    for(int i=0;i<=sampleNum;i++){
      for (int j=0;j<=sampleNum;j++){
        tempX = minX + distanceX*i/sampleNum;
        tempY = minY + distanceY*j/sampleNum;
        if(isInside(tempX,tempY,apexes)){
          samplePoint.add(new Point2D(tempX,tempY));
        }

      }
    }

    return samplePoint;
  }

  private List<Point2D> lineSampling(Point2D start,Point2D end){

    List<Point2D> samplePoints=new ArrayList<>();
    samplePoints.add(start);
    samplePoints.add(end);

    int sampleNum = 8;

    double startX =start.getX();
    double startY = start.getY();
    double distanceX = end.getX() - startX;
    double distanceY = end.getY() - startY;

    for(int i =1;i<=sampleNum;i++){
      samplePoints.add(new Point2D(startX+distanceX*i/sampleNum,startY+distanceY*i/sampleNum));
    }

    return samplePoints;
  }

  private int[] polygon2ConvexHull(int[] apexes){

    int vertexNum = apexes.length/2;

    if(vertexNum<=3){
      return apexes;
    }

    List<Integer> concavePointIndex = new ArrayList<>();
    int flag;


    for(int i=0;i<vertexNum;i++){

      int pointX = apexes[2*i];
      int pointY = apexes[2*i+1];
      int[] tempApexes = new int[vertexNum*2-2];

      flag=0;
      for(int j=0;j<vertexNum*2;j++){
        if(j==2*i||j==2*i+1) continue;
        tempApexes[flag] = apexes[j];
        flag++;
      }

      if(isInside(pointX,pointY,tempApexes)){
        concavePointIndex.add(2*i);
        concavePointIndex.add(2*i+1);
      }
    }

    int[] convexHull = new int[2*vertexNum-concavePointIndex.size()];
    if(convexHull.length<6){
      return apexes;
    }

    flag = 0;
    for(int i = 0;i<vertexNum*2;i++){

      if(concavePointIndex.contains(i)) continue;
      convexHull[flag] = apexes[i];
      flag++;

    }

    return convexHull;

  }

  private boolean intersect(double agentX, double agentY, double pointX, 
                            double pointY, int[]apexes) {
    List<Line2D> lines = GeometryTools2D.pointsToLines(
            GeometryTools2D.vertexArrayToPoints(apexes), true);
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

  private List<Point2D> getDetourPath(){

    Area positionArea = this.agentInfo.getPositionArea();
    EntityID positionID = positionArea.getID();
    double positionX = this.agentInfo.getX();
    double positionY = this.agentInfo.getY();

    //障碍检测
    Blockade nearestBlockade = getNearestBlockade(positionArea);

    //凸包转换
    int[] convexHull = polygon2ConvexHull(nearestBlockade.getApexes());

    //空间采样
    Edge edge = getNextEdge(positionID,nearestBlockade);
    if(edge==null){
      return null;
    }
    List<Point2D> sampleDest = lineSampling(edge.getStart(),edge.getEnd());
    List<Point2D> sampleArea = areaSampling(positionArea);


    // 目标计算

    if(positionID == nearestBlockade.getID()){

    }
    else if(positionArea.getEdgeTo(nearestBlockade.getPosition())!=null){

      Area blockadeArea = (Area) this.worldInfo.getEntity(nearestBlockade.getPosition());
      if (blockadeArea != null) {
        sampleArea.addAll(areaSampling(blockadeArea));
      }

    }else{
      return null;
    }


    List<Point2D> path  = new ArrayList<>();

    //碰撞检测+路径规划
    for(Point2D pointArea:sampleArea){
      for(Point2D pointDest:sampleDest){

        if(intersect(positionX,positionY,pointArea.getX(),pointArea.getY(),convexHull)) continue;
        if(intersect(pointDest.getX(),pointDest.getY(),pointArea.getX(),pointArea.getY(),convexHull)) continue;

        path.add(pointArea);
        path.add(pointDest);
        return path;

      }
    }


    return null;

  }


  private List<Point2D> levyMove(){

    Area positionArea = this.agentInfo.getPositionArea();
    EntityID positionID = positionArea.getID();
    double positionX = this.agentInfo.getX();
    double positionY = this.agentInfo.getY();


    Area area = this.agentInfo.getPositionArea();
    Collection<Blockade> blockades = this.worldInfo.getBlockades(area).stream()
            .filter(Blockade::isApexesDefined).collect(Collectors.toSet());
    blockades.addAll(getNeighborBlockade(area));

    // 空间采样
    List<Point2D> sampleArea = new ArrayList<>();
    List<EntityID> neighborIDs = positionArea.getNeighbours();
    for(EntityID neighborID:neighborIDs){

      Area neighborArea = (Area) this.worldInfo.getEntity(neighborID);
      if(neighborArea!=null){
        sampleArea.addAll(areaSampling(neighborArea));
      }
    }

    for(Point2D point:sampleArea){

      for(Blockade blockade:blockades){

        if(isInside(point.getX(),point.getY(),blockade.getApexes())){
          break;
        }

        if(intersect(positionX,positionY,point.getX(),point.getY(),blockade.getApexes())){
          break;
        }

        List<Point2D> path  = new ArrayList<>();
        path.add(point);
        return path;

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

        times++;
        if(times>10){
          break;
        }

        index = random.nextInt(inRangeEntityArray.length);
        entity = (StandardEntity) inRangeEntityArray[index];

        if (!(entity instanceof Area)){
          continue;
        }
        if (entity.getID()==position){
          continue;
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

  private Blockade getNearestBlockade(Area area){

    Point2D position = new Point2D(this.agentInfo.getX(),this.agentInfo.getY());

    double distance;
    double mindistance= Double.MAX_VALUE;
    Blockade nearestBlockade = null;

    Collection<Blockade> blockades = this.worldInfo.getBlockades(area).stream()
            .filter(Blockade::isApexesDefined).collect(Collectors.toSet());
    blockades.addAll(getNeighborBlockade(area));

    for(Blockade blockade:blockades){

      distance = pointPolyDistance(position,blockade.getApexes());
      if(distance<mindistance){
        mindistance = distance;
      }
      nearestBlockade = blockade;
    }

    return nearestBlockade;

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

  private double pointSegmentDistance(Point2D point, Line2D line){

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


  private boolean isInside(double pX, double pY, int[] apex) {
    Point2D p = new Point2D(pX, pY);
    Vector2D v1 = (new Point2D(apex[apex.length - 2], apex[apex.length - 1])).minus(p);
    Vector2D v2 = (new Point2D(apex[0], apex[1])).minus(p);
    double theta = this.getAngle(v1, v2);

    for (int i = 0; i < apex.length - 2; i += 2) {
      v1 = (new Point2D(apex[i], apex[i + 1])).minus(p);
      v2 = (new Point2D(apex[i + 2], apex[i + 3])).minus(p);
      theta += this.getAngle(v1, v2);
    }
    return Math.round(Math.abs((theta / 2) / Math.PI)) >= 1;
  }

  private boolean needRest(Human agent) {
    int hp = agent.getHP();
    int damage = agent.getDamage();
    if (hp == 0 || damage == 0) {
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


  private EntityID convertArea(EntityID targetID) {
    StandardEntity entity = this.worldInfo.getEntity(targetID);

    if (entity == null) {
      return null;
    }

    if (entity instanceof Human) {
      Human human = (Human) entity;
      if (human.isPositionDefined()) {
        EntityID position = human.getPosition();
        if (this.worldInfo.getEntity(position) instanceof Area) {
          return position;
        }
      }
    } else if (entity instanceof Area) {
      return targetID;
    } else if (entity.getStandardURN() == BLOCKADE) {
      Blockade blockade = (Blockade) entity;
      if (blockade.isPositionDefined()) {
        return blockade.getPosition();
      }
    }
    return null;
  }
}