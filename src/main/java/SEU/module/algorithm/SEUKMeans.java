package SEU.module.algorithm;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.module.algorithm.StaticClustering;

//import com.google.common.collect.Iterables;
//import org.omg.PortableServer.LIFESPAN_POLICY_ID;
import rescuecore2.misc.Pair;
import rescuecore2.misc.collections.LazyMap;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.EntityID;
import java.util.*;


public class SEUKMeans extends StaticClustering {
    private static final String KEY_CLUSTER_SIZE = "SEU.clustering.size";
    private static final String KEY_CLUSTER_CENTER = "SEU.clustering.centers";
    private static final String KEY_CLUSTER_ENTITY = "SEU.clustering.entities.";
    private static final String KEY_ASSIGN_AGENT = "SEU.clustering.assign";
    private static Random random = new Random();
    private int repeatPrecompute;
    private int repeatPreparate;
    private PathPlanning pathPlanning;

    //---------------------CUSTOM---------------------------------------
    /*Register  Weight lists*/
    private List<Integer> FBvalue;
    private List<Integer> PFvalue;
    private List<Integer> ATvalue;

    /*Grab All Kinds of Agents*/
    private Collection<StandardEntity>  ALL_FB;
    private Collection<StandardEntity>  ALL_PF;
    private Collection<StandardEntity>  ALL_AT;

    /*BuriedAgentsRecord*/
    private List<Integer> BAR;
    private List<Integer> clusterBAR;

    /*KmeansVersion*/
    private static boolean isPrint = false;
    private static final String Kmeans_Version = "Abattoir 1.3.3";
    //-------------------------------------------------------------------

    private Collection<StandardEntity> entities;
    private List<StandardEntity> centerList;
    private List<EntityID> centerIDs;
    private Map<Integer, List<StandardEntity>> clusterEntitiesList;
    private List<List<EntityID>> clusterEntityIDsList;
    private int clusterSize = 0;
    private boolean assignAgentsFlag;
    private Map<EntityID, Set<EntityID>> shortestPathGraph;


    public SEUKMeans(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        if (!isPrint) {
            System.out.println("[Kmeans Version    "+Kmeans_Version+"]");
            isPrint = true;
        }
        this.ATvalue = new ArrayList<>();
        this.PFvalue = new ArrayList<>();
        this.FBvalue = new ArrayList<>();
        this.BAR = new ArrayList<>();

        this.clusterEntityIDsList = new ArrayList<>();
        this.centerIDs = new ArrayList<>();
        this.clusterEntitiesList = new HashMap<>();
        this.centerList = new ArrayList<>();
        this.entities = PreprocessFunc(wi.getEntitiesOfType(
                StandardEntityURN.ROAD,
                StandardEntityURN.HYDRANT,
                StandardEntityURN.BUILDING,
                StandardEntityURN.REFUGE,
                StandardEntityURN.GAS_STATION,
                StandardEntityURN.AMBULANCE_CENTRE,
                StandardEntityURN.FIRE_STATION,
                StandardEntityURN.POLICE_OFFICE
               ));
        this.ALL_FB = wi.getEntitiesOfType(StandardEntityURN.FIRE_BRIGADE);
        this.ALL_PF = wi.getEntitiesOfType(StandardEntityURN.POLICE_FORCE);
        this.ALL_AT = wi.getEntitiesOfType(StandardEntityURN.AMBULANCE_TEAM);


        this.pathPlanning = moduleManager.getModule("SEU.module.SEUKMeans.PathPlanning", "adf.impl.module.algorithm.DijkstraPathPlanning");
        this.repeatPrecompute = developData.getInteger("SEU.module.SEUKMeans.repeatPrecompute", 7);
        this.repeatPreparate = developData.getInteger("SEU.module.SEUKMeans.repeatPreparate", 20);
        this.clusterSize = developData.getInteger("SEU.module.SEUKMeans.clusterSize",getK());
        this.assignAgentsFlag = developData.getBoolean("SEU.module.SEUKMeans.assignAgentsFlag", true);
    }

    @Override
    public Clustering updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if(this.getCountUpdateInfo() >= 2) {
            return this;
        }
       // this.centerList.clear();
        //this.clusterEntitiesList.clear();
        return this;
    }

    @Override
    public Clustering precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        if(this.getCountPrecompute() >= 2) {
            return this;
        }
        //this.calcPathBasedPlusPlus(this.repeatPrecompute);
        this.calcStandardPlusPlus(this.repeatPrecompute);
        this.reconstructACE();
        this.entities = null;
        // write
        precomputeData.setInteger(KEY_CLUSTER_SIZE, this.clusterSize);
        precomputeData.setEntityIDList(KEY_CLUSTER_CENTER, this.centerIDs);
        for(int i = 0; i < this.clusterSize; i++) {
            precomputeData.setEntityIDList(KEY_CLUSTER_ENTITY + i, this.clusterEntityIDsList.get(i));
        }
        precomputeData.setBoolean(KEY_ASSIGN_AGENT, this.assignAgentsFlag);
        return this;
    }

    @Override
    public Clustering resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if(this.getCountResume() >= 2) {
            return this;
        }
        this.entities = null;
        // read
        this.clusterSize = precomputeData.getInteger(KEY_CLUSTER_SIZE);
        this.centerIDs = new ArrayList<>(precomputeData.getEntityIDList(KEY_CLUSTER_CENTER));
        this.clusterEntityIDsList = new ArrayList<>(this.clusterSize);
        for(int i = 0; i < this.clusterSize; i++) {
            this.clusterEntityIDsList.add(i, precomputeData.getEntityIDList(KEY_CLUSTER_ENTITY + i));
        }
        this.assignAgentsFlag = precomputeData.getBoolean(KEY_ASSIGN_AGENT);
        return this;
    }

    @Override
    public Clustering preparate() {
        super.preparate();
        if(this.getCountPreparate() >= 2) {
            return this;
        }
       this.calcStandardPlusPlus(this.repeatPreparate);
       this.reconstructACE();
       // this.assginSettings();
        //this.calcStandard(this.repeatPreparate);
        this.entities = null;
        return this;
    }

    @Override
    public int getClusterNumber() {
        //The number of clusters
        return this.clusterSize;
    }

    @Override
    public int getClusterIndex(StandardEntity entity) {
        //return this.getClusterIndex(entity.getID());
        for(int i = 0; i < this.clusterSize; i++) {
            if(this.clusterEntitiesList.get(i).contains(entity)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public int getClusterIndex(EntityID id) {
        for(int i = 0; i < this.clusterSize; i++) {
            if(this.clusterEntityIDsList.get(i).contains(id)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public Collection<StandardEntity> getClusterEntities(int index) {
        List<StandardEntity> result = this.clusterEntitiesList.get(index);
        if(result == null || result.isEmpty()) {
            List<EntityID> list = this.clusterEntityIDsList.get(index);
            result = new ArrayList<>(list.size());
            for(int i = 0; i < list.size(); i++) {
                result.add(i, this.worldInfo.getEntity(list.get(i)));
            }
            this.clusterEntitiesList.put(index, result);
        }

        /*For cluster effect test*/
//        List<EntityID> IDs = new ArrayList<>(this.centerIDs);
//        //System.out.println("centerID size = "+IDs.size());
//        List<StandardEntity> cl = new ArrayList<>();
//        for (int i0 = 0 ; i0 != this.clusterSize; i0 ++)
//        {
//            cl.add(this.worldInfo.getEntity(IDs.get(i0)));
//        }
//
//        return cl;
        return result;
    }

    @Override
    public Collection<EntityID> getClusterEntityIDs(int index) {
        return this.clusterEntityIDsList.get(index);
    }

    @Override
    public Clustering calc() {
        return this;
    }

    public int getK() {

        int FBSize = this.worldInfo.getEntitiesOfType(StandardEntityURN.FIRE_BRIGADE).size();
        int ATSize = this.worldInfo.getEntitiesOfType(StandardEntityURN.AMBULANCE_TEAM).size();
        int PFSize = this.worldInfo.getEntitiesOfType(StandardEntityURN.POLICE_FORCE).size();
        if (this.agentInfo.me().getStandardURN() == StandardEntityURN.AMBULANCE_TEAM) {
            int avr = ATSize /4;
            if (avr == 0) {
                avr = 1;
            }
            //System.out.println("AT: "+avr);
            return avr;
        }
        //else return 5;

        if (this.agentInfo.me().getStandardURN() == StandardEntityURN.FIRE_BRIGADE) {
            int avr = FBSize / 2;
            if (avr == 0) {
                avr = 1;
            }
            //System.out.println("FB: "+avr);
            return avr;
        }

        if (this.agentInfo.me().getStandardURN() == StandardEntityURN.POLICE_FORCE) {
            int avr = PFSize / 2;
            if (avr == 0) {
                avr = 1;
            }
            //System.out.println("PF: "+avr);
            return avr;
        }
        return 10;


    }

    private void calcStandardPlusPlus(int repeat) {
        this.initShortestPath(this.worldInfo);

        List<StandardEntity> entityList = new ArrayList<>(this.entities);
        this.centerList = new ArrayList<>(this.clusterSize);
        this.clusterEntitiesList = new HashMap<>(this.clusterSize);

        //init list
        for (int index = 0; index < this.clusterSize; index++)
        {
            this.clusterEntitiesList.put(index, new ArrayList<>());
            this.centerList.add(index, entityList.get(0));
        }

        //init center
        List<StandardEntity> Raw_CenterList = chooseInitialCenters(clusterSize,entityList);
        int Size = Raw_CenterList.size();
        if (Size != this.clusterSize)
        {
            for (int index = 0; index < Size; index++) {
                StandardEntity centerEntity;
                centerEntity = Raw_CenterList.get(index);
                if (!this.centerList.contains(centerEntity)) {
                    this.centerList.set(index, centerEntity);
                }
            }

        }
        else
            {
                this.centerList = Raw_CenterList;
            }

        //calc center
        for (int i = 0; i < repeat; i++) {
            this.clusterEntitiesList.clear();
            for (int index = 0; index < this.clusterSize; index++) {
                this.clusterEntitiesList.put(index, new ArrayList<>());
            }
            for (StandardEntity entity : entityList) {
                StandardEntity tmp = this.getNearEntityByLine(this.worldInfo, this.centerList, entity);
                this.clusterEntitiesList.get(this.centerList.indexOf(tmp)).add(entity);
            }
            for (int index = 0; index < this.clusterSize; index++) {
                int sumX = 0, sumY = 0;
                for (StandardEntity entity : this.clusterEntitiesList.get(index)) {
                    Pair<Integer, Integer> location = this.worldInfo.getLocation(entity);
                    sumX += location.first();
                    sumY += location.second();
                }
                int centerX = sumX / this.clusterEntitiesList.get(index).size();
                int centerY = sumY / this.clusterEntitiesList.get(index).size();
                StandardEntity center = this.getNearEntityByLine(this.worldInfo, this.clusterEntitiesList.get(index), centerX, centerY);
                if(center instanceof Area) {
                    this.centerList.set(index, center);
                }
                /*else if(center instanceof Human) {
                    this.centerList.set(index, this.worldInfo.getEntity(((Human) center).getPosition()));
                }*/
                else if(center instanceof Blockade) {
                    this.centerList.set(index, this.worldInfo.getEntity(((Blockade) center).getPosition()));
                }
            }
            if  (scenarioInfo.isDebugMode()) { System.out.print("*"); }
        }

        if  (scenarioInfo.isDebugMode()) { System.out.println(); }

        //set entity
        this.clusterEntitiesList.clear();                                    //repull entity
        for (int index = 0; index < this.clusterSize; index++) {
            this.clusterEntitiesList.put(index, new ArrayList<>());
        }
        for (StandardEntity entity : entityList) {
            StandardEntity tmp = this.getNearEntityByLine(this.worldInfo, this.centerList, entity);
            this.clusterEntitiesList.get(this.centerList.indexOf(tmp)).add(entity);
        }
        CaculateWeightValuefunc();
        //System.out.println("2CaculateWeightValue finsh");

        assginSettings();
        //System.out.println("4AssignAgent finsh");

        this.centerIDs = new ArrayList<>();                                           //refresh centerIDs
        for(int i = 0; i < this.centerList.size(); i++) {
            this.centerIDs.add(i, this.centerList.get(i).getID());
        }
        for (int index = 0;index != this.clusterSize;index++)
        {
            if (!this.clusterEntitiesList.get(index).contains(this.centerList.get(index)))
            {
                StandardEntity temp = this.clusterEntitiesList.get(index).get(0);
                this.clusterEntitiesList.get(index).set(0,this.centerList.get(index));
                this.clusterEntitiesList.get(index).add(temp);
            }
            else{
                StandardEntity temp1 = this.clusterEntitiesList.get(index).get(0);
                int addIndex = this.clusterEntitiesList.get(index).indexOf(this.centerList.get(index));
                this.clusterEntitiesList.get(index).set(addIndex,temp1);
                this.clusterEntitiesList.get(index).set(0,this.centerList.get(index));
            }

        }
        for (int index = 0; index < this.clusterSize; index++) {
            List<StandardEntity> entities = this.clusterEntitiesList.get(index);
            List<EntityID> list = new ArrayList<>();
            if (!entities.isEmpty()) {
                for (int i = 0; i < entities.size(); i++) {
                    if (entities.get(i) != null)
                    list.add( entities.get(i).getID());
                }
            }
            this.clusterEntityIDsList.add(index, list);
        }
        InnerTestAppication();
        //

        //System.out.println(" ClusterStandard++ Finished!  K = " + this.clusterSize);
    }

    private void calcPathBasedPlusPlus(int repeat) {
        this.initShortestPath(this.worldInfo);

        List<StandardEntity> entityList = new ArrayList<>(this.entities);
        this.centerList = new ArrayList<>(this.clusterSize);
        this.clusterEntitiesList = new HashMap<>(this.clusterSize);

        //init list
        for (int index = 0; index < this.clusterSize; index++) {
            this.clusterEntitiesList.put(index, new ArrayList<>());
            this.centerList.add(index, entityList.get(0));
        }

        //init center
        List<StandardEntity> Raw_CenterList = chooseInitialCenters(clusterSize,entityList);
        int Size = Raw_CenterList.size();
        if (Size != this.clusterSize)
        {
            for (int index = 0; index < Size; index++) {
                StandardEntity centerEntity;
                centerEntity = Raw_CenterList.get(index);
                if (!this.centerList.contains(centerEntity)) {
                    this.centerList.set(index, centerEntity);
                }
            }

        }
        else
        {
            this.centerList = Raw_CenterList;
        }

        //calc center
        for (int i = 0; i < repeat; i++) {
            this.clusterEntitiesList.clear();
            for (int index = 0; index < this.clusterSize; index++) {
                this.clusterEntitiesList.put(index, new ArrayList<>());
            }
            for (StandardEntity entity : entityList) {
                StandardEntity tmp = this.getNearEntity(this.worldInfo, this.centerList, entity);
                this.clusterEntitiesList.get(this.centerList.indexOf(tmp)).add(entity);
            }
            for (int index = 0; index < this.clusterSize; index++) {
                int sumX = 0, sumY = 0;
                for (StandardEntity entity : this.clusterEntitiesList.get(index)) {
                    Pair<Integer, Integer> location = this.worldInfo.getLocation(entity);
                    sumX += location.first();
                    sumY += location.second();
                }
                int centerX = sumX / clusterEntitiesList.get(index).size();
                int centerY = sumY / clusterEntitiesList.get(index).size();

                //this.centerList.set(index, getNearEntity(this.worldInfo, this.clusterEntitiesList.get(index), centerX, centerY));
                StandardEntity center = this.getNearEntity(this.worldInfo, this.clusterEntitiesList.get(index), centerX, centerY);
                if (center instanceof Area) {
                    this.centerList.set(index, center);
                } else if (center instanceof Human) {
                    this.centerList.set(index, this.worldInfo.getEntity(((Human) center).getPosition()));
                } else if (center instanceof Blockade) {
                    this.centerList.set(index, this.worldInfo.getEntity(((Blockade) center).getPosition()));
                }
            }
            if  (scenarioInfo.isDebugMode()) { System.out.print("*"); }
        }

        if  (scenarioInfo.isDebugMode()) { System.out.println(); }

        //set entity
        this.clusterEntitiesList.clear();
        for (int index = 0; index < this.clusterSize; index++) {
            this.clusterEntitiesList.put(index, new ArrayList<>());
        }
        for (StandardEntity entity : entityList) {
            StandardEntity tmp = this.getNearEntity(this.worldInfo, this.centerList, entity);
            this.clusterEntitiesList.get(this.centerList.indexOf(tmp)).add(entity);
        }
        CaculateWeightValuefunc();
        System.out.println("2CaculateWeightValue finsh");

        assginSettings();
        System.out.println("4AssignAgent finsh");

        this.centerIDs = new ArrayList<>();                                           //refresh centerIDs
        for(int i = 0; i < this.centerList.size(); i++) {
            this.centerIDs.add(i, this.centerList.get(i).getID());
        }
        for (int index = 0;index != this.clusterSize;index++)
        {
            if (!this.clusterEntitiesList.get(index).contains(this.centerList.get(index)))
            {
                StandardEntity temp = this.clusterEntitiesList.get(index).get(0);
                this.clusterEntitiesList.get(index).set(0,this.centerList.get(index));
                this.clusterEntitiesList.get(index).add(temp);
            }
            else{
                StandardEntity temp1 = this.clusterEntitiesList.get(index).get(0);
                int addIndex = this.clusterEntitiesList.get(index).indexOf(this.centerList.get(index));
                this.clusterEntitiesList.get(index).set(addIndex,temp1);
                this.clusterEntitiesList.get(index).set(0,this.centerList.get(index));
            }

        }
        for (int index = 0; index < this.clusterSize; index++) {
            List<StandardEntity> entities = this.clusterEntitiesList.get(index);
            List<EntityID> list = new ArrayList<>();
            if (!entities.isEmpty()) {
                for (int i = 0; i < entities.size(); i++) {
                    if (entities.get(i) != null)
                        list.add( entities.get(i).getID());
                }
            }
            this.clusterEntityIDsList.add(index, list);
        }
        InnerTestAppication();

        System.out.println(" ClusterPathBased++ Finished!  K = " + this.clusterSize);
    }

    private void CaculateWeightValuefunc() {
        //System.out.println(ATvalue.size());
        //List<StandardEntity> l ;
        fillList(FBvalue,this.clusterSize);
        fillList(PFvalue,this.clusterSize);
        fillList(ATvalue,this.clusterSize);

        //System.out.println("1fillList finsh");
        for (int i = 0; i != this.clusterSize;i++) {
            for (StandardEntity se : this.clusterEntitiesList.get(i)) {
                if (se.getStandardURN() == StandardEntityURN.GAS_STATION) {
                    int i1 = 0;
                    i1 = this.FBvalue.get(i);
                    this.FBvalue.set(i, i1 + 1);
                }
                if (se.getStandardURN() == StandardEntityURN.REFUGE) {
                    int i2 = 0;
                    i2 = this.ATvalue.get(i);
                    this.ATvalue.set(i, i2 + 1);
                }
            }
        }
        //System.out.println("fb-->"+FBvalue);
        //System.out.println("at-->"+ATvalue);

    }

    private void assginSettings() {
        if(this.assignAgentsFlag) {
            List<StandardEntity> firebrigadeList = new ArrayList<>(this.worldInfo.getEntitiesOfType(StandardEntityURN.FIRE_BRIGADE));
            List<StandardEntity> policeforceList = new ArrayList<>(this.worldInfo.getEntitiesOfType(StandardEntityURN.POLICE_FORCE));
            List<StandardEntity> ambulanceteamList = new ArrayList<>(this.worldInfo.getEntitiesOfType(StandardEntityURN.AMBULANCE_TEAM));

            if (firebrigadeList.size() > 0)
                this.assignAgents(this.worldInfo, firebrigadeList);
            if (policeforceList.size() > 0)
                this.assignAgents(this.worldInfo, policeforceList);
            if (ambulanceteamList.size() > 0)
                this.assignAgents(this.worldInfo, ambulanceteamList);
        }
    }

    private void assignAgents(WorldInfo world, List<StandardEntity> agentList) {
        int toll  = 0;
        int clusterIndex = 0;
        List<Integer> WeightList;
        boolean flag = false;
        switch (agentList.get(0).getStandardURN())
        {
            case POLICE_FORCE:
                WeightList =  new ArrayList<>(PFvalue);
                break;
            case FIRE_BRIGADE:
                WeightList =  new ArrayList<>(FBvalue);
                break;
            case AMBULANCE_TEAM:
                WeightList =  new ArrayList<>(ATvalue);
                if (this.clusterSize > 4) {
                    flag = true;
                    //System.out.println("scanBuriednessReport" + "\n" + scanBuriedness());
                    scanBuriedness();
                    for (int index = 0; index != 3; index++) {
                        toll += BAR.get(index);
                    }
                }
                break;
            default:
                WeightList = new ArrayList<>(clusterSize);
                break;
        }
        //System.out.println("3assign agent start!");
        while ((agentList.size() > 0 && (!flag)) || ((flag) && agentList.size() > toll) ) {

             StandardEntity center = this.centerList.get(clusterIndex);
             if (WeightList.get(clusterIndex) > 0 )
             {
                 StandardEntity agent = this.getNearAgent(world, agentList, center);
                 this.clusterEntitiesList.get(clusterIndex).add(agent);
                 agentList.remove(agent);
                 agent = this.getNearAgent(world, agentList, center);
                 this.clusterEntitiesList.get(clusterIndex).add(agent);
                 agentList.remove(agent);
                 WeightList.set(clusterIndex,0);
             }
             else
             {
                 StandardEntity agent = this.getNearAgent(world, agentList, center);
                 this.clusterEntitiesList.get(clusterIndex).add(agent);
                 agentList.remove(agent);
             }

             clusterIndex++;
             if (clusterIndex >= this.clusterSize) {
               clusterIndex = 0;
             }
        }
        if (flag)
        {
           //System.out.println("clusterBAR:"+clusterBAR);
           int temp1 = 0;
            for (int index  = 0; index != this.clusterSize;index ++)
           {
               while (clusterBAR.get(index) > 0)
               {
                   StandardEntity center = this.centerList.get(index);
                   StandardEntity agent = this.getNearAgent(world, agentList, center);
                   this.clusterEntitiesList.get(index).add(agent);
                   agentList.remove(agent);
                   temp1 = clusterBAR.get(index);
                   clusterBAR.set(index,temp1-1);
               }
           }
           int num = 0;
            List<Integer> at_ = new ArrayList<>();
            fillList(at_,this.clusterSize);
            for (StandardEntity se: ALL_AT)
            {
//               System.out.println("agentList:"+agentList.size());
//               System.out.println("at_:"+at_.size() );
//               System.out.println(" this.getClusterIndex(se):"+this.getClusterIndex(se));
                    num = at_.get(this.getClusterIndex(se));
                    at_.set(this.getClusterIndex(se), num + 1);

            }
           //System.out.println("atAssgin:"+at_);
        }
    }

    private StandardEntity getNearEntityByLine(WorldInfo world, List<StandardEntity> srcEntityList, StandardEntity targetEntity) {
        Pair<Integer, Integer> location = world.getLocation(targetEntity);
        return this.getNearEntityByLine(world, srcEntityList, location.first(), location.second());
    }

    private StandardEntity getNearEntityByLine(WorldInfo world, List<StandardEntity> srcEntityList, int targetX, int targetY) {
        StandardEntity result = null;
        for(StandardEntity entity : srcEntityList) {
            result = ((result != null) ? this.compareLineDistance(world, targetX, targetY, result, entity) : entity);
        }
        return result;
    }

    private StandardEntity getNearAgent(WorldInfo worldInfo, List<StandardEntity> srcAgentList, StandardEntity targetEntity) {
//        StandardEntity result = null;
//
//        List<StandardEntity> list = srcAgentList;
//        list.sort(new SEUKMeans.DistanceSorter(this.worldInfo, targetEntity));
//        if (list.size() > 0)
//        result = list.get(0);
//        return result;
        StandardEntity result = null;
        for (StandardEntity agent : srcAgentList) {
            Human human = (Human)agent;
            if (result == null) {
                result = agent;
            }
            else {
                if (this.comparePathDistance(worldInfo, targetEntity, result, worldInfo.getPosition(human)).equals(worldInfo.getPosition(human))) {
                    result = agent;
                }
            }
        }
        return result;
    }

    private StandardEntity getNearEntity(WorldInfo worldInfo, List<StandardEntity> srcEntityList, int targetX, int targetY) {
        StandardEntity result = null;
        for (StandardEntity entity : srcEntityList) {
            result = (result != null) ? this.compareLineDistance(worldInfo, targetX, targetY, result, entity) : entity;
        }
        return result;
    }

    private Point2D getEdgePoint(Edge edge) {
        Point2D start = edge.getStart();
        Point2D end = edge.getEnd();
        return new Point2D(((start.getX() + end.getX()) / 2.0D), ((start.getY() + end.getY()) / 2.0D));
    }

    private double getDistance(StandardEntity from,StandardEntity to)
    {
        return (double)worldInfo.getDistance(from,to);
    }

    private double getDistance(double fromX, double fromY, double toX, double toY) {
        double dx = fromX - toX;
        double dy = fromY - toY;
        return Math.hypot(dx, dy);
    }

    private double getDistance(Pair<Integer, Integer> from, Point2D to) {
        return getDistance(from.first(), from.second(), to.getX(), to.getY());
    }

    private double getDistance(Pair<Integer, Integer> from, Edge to) {
        return getDistance(from, getEdgePoint(to));
    }

    private double getDistance(Point2D from, Point2D to) {
        return getDistance(from.getX(), from.getY(), to.getX(), to.getY());
    }

    private double getDistance(Edge from, Edge to) {
        return getDistance(getEdgePoint(from), getEdgePoint(to));
    }

    private StandardEntity compareLineDistance(WorldInfo worldInfo, int targetX, int targetY, StandardEntity first, StandardEntity second) {
        Pair<Integer, Integer> firstLocation = worldInfo.getLocation(first);
        Pair<Integer, Integer> secondLocation = worldInfo.getLocation(second);
        double firstDistance = getDistance(firstLocation.first(), firstLocation.second(), targetX, targetY);
        double secondDistance = getDistance(secondLocation.first(), secondLocation.second(), targetX, targetY);
        return (firstDistance < secondDistance ? first : second);
    }

    private StandardEntity getNearEntity(WorldInfo worldInfo, List<StandardEntity> srcEntityList, StandardEntity targetEntity) {
        StandardEntity result = null;
        for (StandardEntity entity : srcEntityList) {
            result = (result != null) ? this.comparePathDistance(worldInfo, targetEntity, result, entity) : entity;
        }
        return result;
    }

    private StandardEntity comparePathDistance(WorldInfo worldInfo, StandardEntity target, StandardEntity first, StandardEntity second) {
        double firstDistance = getPathDistance(worldInfo, shortestPath(target.getID(), first.getID()));
        double secondDistance = getPathDistance(worldInfo, shortestPath(target.getID(), second.getID()));
        return (firstDistance < secondDistance ? first : second);
    }

    private double getPathDistance(WorldInfo worldInfo, List<EntityID> path) {
        if (path == null) return Double.MAX_VALUE;
        if (path.size() <= 1) return 0.0D;

        double distance = 0.0D;
        int limit = path.size() - 1;

        Area area = (Area)worldInfo.getEntity(path.get(0));
        distance += getDistance(worldInfo.getLocation(area), area.getEdgeTo(path.get(1)));
        area = (Area)worldInfo.getEntity(path.get(limit));
        distance += getDistance(worldInfo.getLocation(area), area.getEdgeTo(path.get(limit - 1)));

        for(int i = 1; i < limit; i++) {
            area = (Area)worldInfo.getEntity(path.get(i));
            distance += getDistance(area.getEdgeTo(path.get(i - 1)), area.getEdgeTo(path.get(i + 1)));
        }
        return distance;
    }

    private void initShortestPath(WorldInfo worldInfo) {
        Map<EntityID, Set<EntityID>> neighbours = new LazyMap<EntityID, Set<EntityID>>() {
            @Override
            public Set<EntityID> createValue() {
                return new HashSet<>();
            }
        };
        for (Entity next : worldInfo) {
            if (next instanceof Area) {
                Collection<EntityID> areaNeighbours = ((Area) next).getNeighbours();
                neighbours.get(next.getID()).addAll(areaNeighbours);
            }
        }
        for (Map.Entry<EntityID, Set<EntityID>> graph : neighbours.entrySet()) {// fix graph
            for (EntityID entityID : graph.getValue()) {
                neighbours.get(entityID).add(graph.getKey());
            }
        }
        this.shortestPathGraph = neighbours;
    }

    private List<EntityID> shortestPath(EntityID start, EntityID... goals) {
        return shortestPath(start, Arrays.asList(goals));
    }

    private List<EntityID> shortestPath(EntityID start, Collection<EntityID> goals) {
        List<EntityID> open = new LinkedList<>();
        Map<EntityID, EntityID> ancestors = new HashMap<>();
        open.add(start);       //添加出发点start
        EntityID next;
        boolean found = false;
        ancestors.put(start, start);
        do {
            next = open.remove(0);
            if (isGoal(next, goals)) {
                found = true;
                break;
            }
            Collection<EntityID> neighbours = shortestPathGraph.get(next);
            if (neighbours.isEmpty()) continue;

            for (EntityID neighbour : neighbours) {
                if (isGoal(neighbour, goals)) {
                    ancestors.put(neighbour, next);    //从next去往neighbour
                    next = neighbour;
                    found = true;
                    break;
                }
                else if (!ancestors.containsKey(neighbour)) {
                    open.add(neighbour);
                    ancestors.put(neighbour, next);
                }
            }
        } while (!found && !open.isEmpty());
        if (!found) {
            // No path
            return null;
        }
        // Walk back from goal to start
        EntityID current = next;
        List<EntityID> path = new LinkedList<>();
        do {
            path.add(0, current);
            current = ancestors.get(current);
            if (current == null) throw new RuntimeException("Found a node with no ancestor! Something is broken.");
        } while (current != start);
        return path;
    }

    private boolean isGoal(EntityID e, Collection<EntityID> test) {
        return test.contains(e);
    }

    private  List<StandardEntity> chooseInitialCenters(int k ,final Collection<StandardEntity> points) {
        // Convert to list for indexed access. Make it unmodifiable, since removal of items
        // would screw up the logic of this method.
        final List<StandardEntity> pointList = Collections.unmodifiableList(new ArrayList<StandardEntity> (points));

        // The number of points in the list.
        final int numPoints = pointList.size();

        // Set the corresponding element in this array to indicate when
        // elements of pointList are no longer available.
        final boolean[] taken = new boolean[numPoints];

        // The resulting list of initial centers.
        final List<StandardEntity> resultSet = new ArrayList<StandardEntity>();

        // Choose one center uniformly at random from among the data points.

        final int firstPointIndex = random.nextInt(numPoints);
        final StandardEntity firstPoint = pointList.get(firstPointIndex);
        resultSet.add(firstPoint);

        // Must mark it as taken
        taken[firstPointIndex] = true;

        // To keep track of the minimum distance squared of elements of
        // pointList to elements of resultSet.
        final double[] minDistSquared = new double[numPoints];

        // Initialize the elements.  Since the only point in resultSet is firstPoint,
        // this is very easy.
        for (int i = 0; i < numPoints; i++) {
              if (i != firstPointIndex)
              { // That point isn't considered
                  double d = getDistance(firstPoint, pointList.get(i));
                  minDistSquared[i] = d*d;
              }
        }

        while (resultSet.size() < k) {
            // Sum up the squared distances for the points in pointList not
            // already taken.
            double distSqSum = 0.0;

            for (int i = 0; i < numPoints; i++) {
                 if (!taken[i])
                 {
                     distSqSum += minDistSquared[i];
                 }
            }
            // Add one new data point as a center. Each point x is chosen with
            // probability proportional to D(x)2
            //final double r = random.nextDouble() * distSqSum;
            final double r = 0.87 * distSqSum;
            // The index of the next point to be added to the resultSet.
            int nextPointIndex = -1;

            // Sum through the squared min distances again, stopping when
            // sum >= r.
            double sum = 0.0;
            for (int i = 0; i < numPoints; i++) {
                  if (!taken[i])
                  {
                     sum += minDistSquared[i];
                     if (sum >= r)
                     {
                       nextPointIndex = i;
                       break;
                     }
                  }
            }
            // If it's not set to >= 0, the point wasn't found in the previous
            // for loop, probably because distances are extremely small.  Just pick
            // the last available point.
            if (nextPointIndex == -1)
            {
                for (int i = numPoints - 1; i >= 0; i--)
                {
                     if (!taken[i])
                     {
                        nextPointIndex = i;
                        break;
                     }
                }
            }
            // We found one.
            if (nextPointIndex >= 0)
            {
                final StandardEntity p = pointList.get(nextPointIndex);
                resultSet.add(p);

                // Mark it as taken.
                taken[nextPointIndex] = true;

                if (resultSet.size() < k)
                {
                   // Now update elements of minDistSquared.  We only have to compute
                   // the distance to the new center to do this.
                   for (int j = 0; j < numPoints; j++)
                   {
                       // Only have to worry about the points still not taken.
                       if (!taken[j])
                       {
                           double d = getDistance(p, pointList.get(j));
                           double d2 = d * d;
                           if (d2 < minDistSquared[j])
                           {
                               minDistSquared[j] = d2;
                           }
                       }
                   }
                }

            } else {
                // None found --
                // Break from the while loop to prevent
                // an infinite loop.
                break;
             }
        }

       return resultSet;
    }

    private class DistanceSorter implements Comparator<StandardEntity> {
        private StandardEntity reference;
        private WorldInfo worldInfo;

        DistanceSorter(WorldInfo wi, StandardEntity reference) {
            this.reference = reference;
            this.worldInfo = wi;
        }

        public int compare(StandardEntity a, StandardEntity b) {
            int d1 = this.worldInfo.getDistance(this.reference, a);
            int d2 = this.worldInfo.getDistance(this.reference, b);
            return d1 - d2;
        }
    }

    private List<EntityID> IDtoEntity(List<StandardEntity> sl) {
        List<EntityID> idl = new ArrayList<>();
        for (StandardEntity se : sl)
        {
            idl.add(se.getID());
        }
        return idl;
    }

    private void fillList(List< Integer> l,int Nsize) {
        if (l.isEmpty())
            l.clear();
        for (int i6 = 0; i6 != Nsize;i6++)
        l.add(0);
    }

    private void InnerTestAppication() {
        String ErrorInfo1 = "<<<  CheckingEntity SUCCESSFUL!  ";
        String ErrorInfo2 = "<<<  CheckingEntityID SUCCESSFUL!  ";
        for (int i = 0 ;i != this.clusterSize;i++)
        {
            if (this.clusterEntitiesList.get(i).get(0).getID() != this.centerList.get(i).getID())
            {
                ErrorInfo1 = "sorry,the first element of " +
                        "clusterEntitesList doesn't match " +
                        "the responding clusterList";
                System.out.println(ErrorInfo1);
                break;
            }
        }
        for (int i = 0 ;i != this.clusterSize;i++)
        {
            if (this.clusterEntityIDsList.get(i).get(0) != this.centerList.get(i).getID())
            {
                ErrorInfo2 = "sorry,the first element of " +
                        "clusterEntitesIDList doesn't match " +
                        "the responding clusterList";
                System.out.println(ErrorInfo2);
                break;
            }
        }
        //
        //


    }

    private Collection<StandardEntity> PreprocessFunc(Collection<StandardEntity> el) {
        List<StandardEntity> resultList = new ArrayList<>(el);
        //System.out.println("before preprocess-->  "+resultList.size());
        /*remove no edge road*/
        List<Road> TempList = new ArrayList<>();
        for (StandardEntity se : el )
        {
            if (se.getStandardURN() == StandardEntityURN.ROAD)
            {
                TempList.add((Road)se);
            }
        }
        resultList.removeAll(TempList);
        for (int i = 0 ; i < TempList.size();i ++)
        {
            if (!isWrapped(TempList.get(i)))
            {
                TempList.remove(i);
                i--;
            }
        }
        resultList.addAll(TempList);

        //System.out.println("after preprocess-->  "+resultList.size());

        return resultList;
    }

    private boolean isWrapped(Road road) {
        Boolean flag = false;
        List<EntityID> RneigbID = new ArrayList<>(road.getNeighbours());
        List<StandardEntity> Rneigb = new ArrayList<>();
        for (EntityID eid : RneigbID)
        {
            StandardEntity se = this.worldInfo.getEntity(eid);
            Rneigb.add(se);
        }
        for(int i = 0 ; i != Rneigb.size();i++)
        {
            if ((Rneigb.get(i).getStandardURN() == StandardEntityURN.BUILDING)||
                (Rneigb.get(i).getStandardURN() == StandardEntityURN.GAS_STATION)||
                (Rneigb.get(i).getStandardURN() == StandardEntityURN.REFUGE)||
                (Rneigb.get(i).getStandardURN() == StandardEntityURN.AMBULANCE_CENTRE)||
                (Rneigb.get(i).getStandardURN() == StandardEntityURN.FIRE_STATION)||
                (Rneigb.get(i).getStandardURN() == StandardEntityURN.POLICE_OFFICE)||
                (Rneigb.get(i).getStandardURN() == StandardEntityURN.HYDRANT))
            {
                flag =true;
                break;
            }
        }
        if (Rneigb.size() == 3)
            flag = true;
        return flag;

    }

    private String scanBuriedness() {
        List<StandardEntity> fbList = new ArrayList<>(this.ALL_FB);
        List<StandardEntity> pfList = new ArrayList<>(this.ALL_PF);
        List<StandardEntity> atList = new ArrayList<>(this.ALL_AT);
        clusterBAR = new ArrayList<>();
        fillList(clusterBAR,this.clusterSize);
        fillList(BAR,this.clusterSize);
        int f = 0,p = 0,a = 0;
        int temp = 0;
        for (StandardEntity se : fbList)
        {
            Human h = (Human)se;
            if (h.isPositionDefined())
            {
               if (this.worldInfo.getEntity(h.getPosition()).getStandardURN() == StandardEntityURN.BUILDING) {
                   temp = clusterBAR.get( getClusterIndex(se));
                   clusterBAR.set( getClusterIndex(se),temp+1);
                   f++;
                   break;
               }
            }
        }
        for (StandardEntity se : pfList)
        {
            Human h = (Human)se;
            if (h.isPositionDefined())
            {
                if (this.worldInfo.getEntity(h.getPosition()).getStandardURN() == StandardEntityURN.BUILDING) {
                    temp = clusterBAR.get( getClusterIndex(se));
                    clusterBAR.set( getClusterIndex(se),temp+1);
                    p ++;
                    break;
                }
            }
        }
//        for (StandardEntity se : atList)
//        {
//            Human h = (Human)se;
//            if (h.isPositionDefined())
//            {
//                if (this.worldInfo.getEntity(h.getPosition()).getStandardURN() == StandardEntityURN.BUILDING) {
//                    temp = clusterBAR.get( getClusterIndex(se));
//                    clusterBAR.set( getClusterIndex(se),temp+1);
//                    a ++;
//                    break;
//                }
//            }
//        }
        String result = "";
        BAR.set(0,f);
        BAR.set(1,p);
        BAR.set(2,a);

        result += "fb->"+String.valueOf(f)+"  ";
        result += "pf->"+String.valueOf(p)+"  ";
        result += "at->"+String.valueOf(a)+"  ";

        result += "\n"+"[fb,pf,at]=["+fbList.size()+","+pfList.size()+","+atList.size()+"] ";
        return result;

    }

    public static List<StandardEntity> getNeighbors(Area area, WorldInfo wi) {
        List<EntityID> nIDs = area.getNeighbours();
        if (nIDs != null) {
            List<StandardEntity> neighbors = new ArrayList<>(nIDs.size());
            for (EntityID nID : nIDs) {
                neighbors.add(wi.getEntity(nID));
            }
            return neighbors;
        }
        return new ArrayList<StandardEntity>(1);
    }

    public static List<EntityID> getEntityList(List<StandardEntity> entities) {
        List<EntityID> IDs = new ArrayList<>(entities.size());
        for (StandardEntity se : entities) {
            IDs.add(se.getID());
        }
        return IDs;
    }

    protected void reconstructACE() {
        boolean isOK = true;
        for (int i=0;i!=clusterEntitiesList.size();i++)
        {
            if (clusterEntitiesList.get(i).size() < 10)
                isOK = false;
        }
        if (clusterEntitiesList.size() <5)
            isOK = false;
        if (isOK)
        {
            for (Map.Entry<Integer,List<StandardEntity>> entry : clusterEntitiesList.entrySet()) {
                List<StandardEntity> cEntities = entry.getValue();
                List<StandardEntity> newClusterEntities = reconstructCE(cEntities);
                clusterEntitiesList.put(entry.getKey(), newClusterEntities);
            }
            updateClusterEntityIDList();
            System.out.println("reconstructACE finished! ");
        }
        else System.out.println("reconstructACE skipped! ");


    }

    protected void updateClusterEntityIDList() {
        clusterEntityIDsList.clear();
        for (int i = 0; i < clusterEntitiesList.size(); i++) {
            List<StandardEntity> entities = clusterEntitiesList.get(i);
            clusterEntityIDsList.add(getEntityList(entities));
        }
    }

    protected List<StandardEntity> reconstructCE(List<StandardEntity> cEntities) {
        List<List<StandardEntity>> paths = new ArrayList<>();
        Set<StandardEntity> entitySet = new HashSet<>(cEntities);
        Queue<StandardEntity> queue = new ArrayDeque<>(entitySet.size());
        Set<StandardEntity> visited = new HashSet<>(entitySet.size());

        while (!entitySet.isEmpty()) {
            List<StandardEntity> path = new ArrayList<>();
            //if ()
//            queue.offer(Iterables.get(entitySet, 0));
            Iterator<StandardEntity> it= entitySet.iterator();
            queue.offer(it.next());
            while (!queue.isEmpty()) {
                StandardEntity current = queue.poll();

                visited.add(current);
                entitySet.remove(current);
                path.add(current);
                boolean hasNeighbor = false;
                if (current instanceof Area) {
                    Area currentArea = (Area)current;
                    List<StandardEntity> neighbors = getNeighbors(currentArea, worldInfo);
                    for (StandardEntity n : neighbors) {
                        if (!visited.contains(n) && entitySet.contains(n)) {
                            visited.add(n);
                            queue.offer(n);
                            hasNeighbor = true;
                            entitySet.remove(n);
                        }
                    }
                }
            }
            if (path.size() > 0) {
                paths.add(path);
            }
        }



        while(paths.size() > 1) {
            List<StandardEntity> longest = paths.get(0);
            List<StandardEntity> secondLongest = paths.get(1);

            if (secondLongest.size() > longest.size()) {
                List<StandardEntity> temp = longest;
                longest = secondLongest;
                secondLongest = temp;
            }
            // find the longest and the second longest paths
            for (int i = 2; i < paths.size(); i++) {
                List<StandardEntity> path = paths.get(i);
                if (path.size() > longest.size()) {
                    longest = path;
                } else if (path.size() > secondLongest.size()) {
                    secondLongest = path;
                }
            }


            List<EntityID> shortestPath = null;
            int shortestPathLength = Integer.MAX_VALUE;
            for (int i = 0; i < secondLongest.size(); i++) {

                List<EntityID> path = pathPlanning.setFrom(secondLongest.get(i).getID()).setDestination(getEntityList(longest)).calc().getResult();
                if (path != null) {
                    if (path.size() < shortestPathLength) {
                        shortestPathLength = path.size();
                        shortestPath = path;
                    }
                }
            }

            for (int i = 0; i < shortestPath.size(); i++) {
                longest.add(worldInfo.getEntity(shortestPath.get(i)));
            }
            for (int i = 0; i < secondLongest.size(); i++) {
                longest.add(secondLongest.get(i));
            }

            paths.remove(secondLongest);
        }

        if (paths.size() > 0) {

            return paths.get(0);
        }
        else {
            return new ArrayList<StandardEntity>();
        }
    }




    /*------------------Code Conserved Domain------------------------------*/

    /* CC : Contour coefficient*/
//    private double GetCC(int K,int repeat)
//    {
//        Map<Integer, List<StandardEntity>>  GCC_clusterEntitiesList = new HashMap<>();
//        List<StandardEntity> GCC_centerList = new ArrayList<>();
//        List<StandardEntity> entityList = new ArrayList<>(this.entities);
//        //init list
//        for (int index = 0; index < K; index++) {
//            GCC_clusterEntitiesList.put(index, new ArrayList<>());
//            GCC_centerList.add(index, entityList.get(0));
//        }
//        //init center
//        List<StandardEntity> Raw_CenterList = chooseInitialCenters(K,entityList);
//        int Size = Raw_CenterList.size();
//        if (Size != K)
//        {
//            for (int index = 0; index < Size; index++) {
//                StandardEntity centerEntity;
//                centerEntity = Raw_CenterList.get(index);
//                if (!GCC_centerList.contains(centerEntity)) {
//                    GCC_centerList.set(index, centerEntity);
//                }
//            }
//
//        }
//        else
//        {
//            GCC_centerList.clear();
//            GCC_centerList = Raw_CenterList;
//        }
//        for (int i = 0; i < repeat; i++)
//        {
//            GCC_clusterEntitiesList.clear();
//            for (int index = 0; index < K; index++) {
//                GCC_clusterEntitiesList.put(index, new ArrayList<>());
//            }
//            for (StandardEntity entity : entityList) {
//                StandardEntity tmp = this.getNearEntity(this.worldInfo, GCC_centerList, entity);
//                GCC_clusterEntitiesList.get(GCC_centerList.indexOf(tmp)).add(entity);
//            }
//            for (int index = 0; index < K; index++) {
//                int sumX = 0, sumY = 0;
//                for (StandardEntity entity : GCC_clusterEntitiesList.get(index)) {
//                    Pair<Integer, Integer> location = this.worldInfo.getLocation(entity);
//                    sumX += location.first();
//                    sumY += location.second();
//                }
//                int centerX = sumX / GCC_clusterEntitiesList.get(index).size();
//                int centerY = sumY / GCC_clusterEntitiesList.get(index).size();
//
//                //this.centerList.set(index, getNearEntity(this.worldInfo, this.clusterEntitiesList.get(index), centerX, centerY));
//                StandardEntity center = this.getNearEntity(this.worldInfo, GCC_clusterEntitiesList.get(index), centerX, centerY);
//                if (center instanceof Area) {
//                    GCC_centerList.set(index, center);
//                } else if (center instanceof Human) {
//                    GCC_centerList.set(index, this.worldInfo.getEntity(((Human) center).getPosition()));
//                } else if (center instanceof Blockade) {
//                    GCC_centerList.set(index, this.worldInfo.getEntity(((Blockade) center).getPosition()));
//                }
//            }
//
//        }
//
//
//
//        GCC_clusterEntitiesList.clear();
//        for (int index = 0; index < K; index++) {
//            GCC_clusterEntitiesList.put(index, new ArrayList<>());
//        }
//        for (StandardEntity entity : entityList) {
//            StandardEntity tmp = this.getNearEntity(this.worldInfo, GCC_centerList, entity);
//            GCC_clusterEntitiesList.get(GCC_centerList.indexOf(tmp)).add(entity);
//        }
//
//        //开始计算轮廓系数
//        double cc = 0;
//        int size = entityList.size();
//        double a = 0;
//        List<Double> B = new ArrayList<Double>(size);
//        double b = 0;
//        int index = 0;
//        List<Double> AB = new ArrayList<Double>(size);
//        int selfIndex = 0;
//        for (StandardEntity everyOne : entityList)
//        {
//
//            for (int i = 0;i < GCC_centerList.size();i++)
//            {
//                List<StandardEntity> l = GCC_clusterEntitiesList.get(i);
//                if (l.contains(everyOne))
//                {
//                    selfIndex = i;
//                    break;
//                }
//            }
//
//            //计算该点到同聚类里其他点不相似度（距离）的平均值a
//            List<StandardEntity> selfCluster = GCC_clusterEntitiesList.get(selfIndex);
//            for (StandardEntity otherOne : selfCluster)
//            {
//                if (everyOne != otherOne)
//                {
//                    a += getDistance(everyOne,otherOne);
//                }
//            }
//            a /= selfCluster.size();
//
//            //计算该点到其他聚类的平均不相似度（距离）的最小值b
//            //目前平均不相似度（距离）暂由与其他聚类中心点的不相似度（距离）代替
//            StandardEntity selfClusterCenter = GCC_centerList.get(selfIndex);
//
//            for (StandardEntity otherCenter :GCC_centerList)
//            {
//
//                if (selfClusterCenter != otherCenter)
//                {
//                    B.add(getDistance(otherCenter,everyOne));
//                }
//            }
//            b =  Collections.min(B);
//            double c = 0;
//             c = (a > b)? a : b;
//            AB.add((b-a)/c);
//
//        }
//        for(double value : AB)
//        {
//           cc += value;
//        }
//        cc /=size;
//        GCC_centerList.clear();
//        GCC_clusterEntitiesList.clear();
//        if ((cc<=1)&&(cc>=-1))
//            System.out.println("k="+K+" , cc="+cc);
//        else
//            System.out.println("GetCC failed!");
//        return cc;
//    }

}

