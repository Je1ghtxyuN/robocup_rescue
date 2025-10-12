package SEU.module.algorithm.PathPlanning;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.module.algorithm.StaticClustering;
import adf.core.debug.DefaultLogger;
import org.apache.log4j.Logger;

import SEU.module.algorithm.Astar;
import SEU.module.algorithm.Couple;

import rescuecore2.misc.collections.LazyMap;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.PoliceForce;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.EntityID;
import java.util.*;

public class SEUPathPlanning extends PathPlanning {

    private final static double INVALID_PENALTY = 15.0;
    private Map<EntityID, Set<Couple<EntityID>>> invalidz = new HashMap<>();

    private Map<EntityID, Set<EntityID>> graph;
    private EntityID from;
    private Collection<EntityID> targets;
    private List<EntityID> result;
    private List<EntityID> previousPath = new ArrayList<>();
    private EntityID previousTarget = null;
    private Logger logger;
    private PathHelper pathhelper;
    private StaticClustering highways;
    

    public SEUPathPlanning(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        logger = DefaultLogger.getLogger(agentInfo.me());
        this.init();
        this.pathhelper = new PathHelper(ai, wi);
        this.highways = moduleManager.getModule("SEU.module.algorithm.SEUHighways");
        this.registerModule(this.highways);
    }

    private void init() {
        Map<EntityID, Set<EntityID>> neighbours = new LazyMap<EntityID, Set<EntityID>>() {
            @Override
            public Set<EntityID> createValue() {
                return new HashSet<>();
            }
        };
        for (Entity next : this.worldInfo) {
            if (next instanceof Area) {
                Collection<EntityID> areaNeighbours = ((Area) next).getNeighbours();
                neighbours.get(next.getID()).addAll(areaNeighbours);
            }
        }
        this.graph = neighbours;
    }

    @Override
    public List<EntityID> getResult() {
        return this.result;
    }

    @Override
    public PathPlanning setFrom(EntityID id) {
        this.from = id;
        return this;
    }

    @Override
    public PathPlanning setDestination(Collection<EntityID> targets) {
        this.targets = targets;
        return this;
    }

    @Override
    public PathPlanning updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        this.pathhelper.updateInfo(messageManager);
        return this;
    }

    @Override
    public PathPlanning precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        return this;
    }

    @Override
    public PathPlanning resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        return this;
    }

    @Override
    public PathPlanning preparate() {
        super.preparate();
        return this;
    }
 
    @Override
    public PathPlanning calc() {
        result = null;
        if (this.from == null ) {       	
        	return this;
        }
        if (targets == null)  {
            return this;
        }
		if (targets.isEmpty()){
        	return this;
        }
        List<EntityID> path = null;

        if (targets.contains(previousTarget) && previousPath.contains(from)) {
            ArrayList<EntityID> temp = new ArrayList<>();
            for (EntityID aPreviousPath : previousPath) {
                if (!from.equals(aPreviousPath)) {
                    temp.add(aPreviousPath);
                } else {
                    break;
                }
            }
            previousPath.removeAll(temp);
            path = previousPath;
        }

        if (path == null || path.isEmpty()) {
            double distance=Double.MAX_VALUE;
            EntityID finallytarget=null;
            for (EntityID target: targets) {
                if(distance>this.worldInfo.getDistance(this.from, target)){
                    distance=this.worldInfo.getDistance(this.from, target);
                    finallytarget=target;
                }
                
            }
            //logger.debug("choose:" + finallytarget);
            path = getShortestPath(this.from, finallytarget);
            previousPath = path;
            
        }
        
        if (path != null && !path.isEmpty()) {         
            result = path;
            
            //logger.debug("path:" + path);
        }else{        
            //logger.debug("-----path is null-----");   	
        }
        return this;
    }

    private double computeCost(EntityID id, Astar.HeuristicNode ancestor) {
        double d = this.computeDistance(id, ancestor.getID());
        if (this.isHighway(ancestor.getID())) {
            return d;  // 高速公路成本不变
        }
        d *= 3.0;  // 非高速公路成本惩罚
        if (this.isInvalidEdge(id, ancestor)) {
            d *= INVALID_PENALTY;  // 障碍道路额外惩罚
        }
        return d;
    }

    private double computeDistance(EntityID id, EntityID ancestor) {
        return this.worldInfo.getDistance(id, ancestor);
        //final Area area1 = (Area)this.worldInfo.getEntity(ancestor);
        //final Area area2 = (Area)this.worldInfo.getEntity(id);

        //final Point2D p1 = new Point2D(area1.getX(), area1.getY());
        //final Point2D p2 = area1.getEdgeTo(id).getLine().getPoint(0.5);
        //final Point2D p3 = new Point2D(area2.getX(), area2.getY());

        //double ret = 0.0;
        //ret += GeometryTools2D.getDistance(p1, p2);
        //ret += GeometryTools2D.getDistance(p2, p3);
        //return ret;
    }

    private boolean isInvalidEdge(EntityID id, EntityID prev, EntityID next) {
        if (!this.invalidz.containsKey(id)) {
        return false;
        }

        final Set<Couple<EntityID>> invalids = this.invalidz.get(id);
        if (invalids.contains(new Couple<>(prev, next))) {
        return true;
        }
        if (invalids.contains(new Couple<>(next, prev))) {
        return true;
        }
        return false;
    }


    private boolean isInvalidEdge(EntityID id, Astar.HeuristicNode ancestor) {
        final EntityID node = ancestor.getID();
        final EntityID prev = ancestor.getAncestor();
        final EntityID next = id;
        return this.isInvalidEdge(node, prev, next);
    }

    private boolean isHighway(EntityID id) {
        final int time = this.agentInfo.getTime();
        if (time < 30) {
            return false;  // 模拟开始30步内不使用高速公路
        }
        return this.highways.getClusterIndex(id) >= 0;
    }
    
    public List<EntityID> getShortestPath(EntityID source, EntityID destination) {
        if (destination == null) {
            System.err.println("ERROR: " + agentInfo.toString() + " Destination is null......");
            return new ArrayList<EntityID>();
        }        
        Node sourceNode=new Node(source);
        Node destinationNode=new Node(destination);
        Set<Node> open = new HashSet<Node>();
        Set<EntityID> closed = new HashSet<EntityID>();
        Node current;
        sourceNode.setG(0);
        sourceNode.setCost(0);
        sourceNode.setParent(null);
        destinationNode.setParent(null);
        open.add(sourceNode);
        boolean found = false;
        if (sourceNode.getId().equals(destinationNode.getId())) {           
            found = true;
            this.pathhelper.unpassable.clear();
            return getPath(sourceNode);
        } 
        Human me=(Human)this.agentInfo.me();
        
        while (open.size()!= 0) {
            current = Collections.min(open);
            if (current.getId().equals(destinationNode.getId())) {
                destinationNode.setParent(current.getParent());                    
                found = true;
                break;
            } 
            open.remove(current);
            closed.add(current.getId());
            Collection<EntityID> neighbours = graph.get(current.getId());
            if (neighbours.isEmpty()) {
                continue;
            }
            for (EntityID neighbour : neighbours) {
                Node neighbourNode=new Node(neighbour);
                
                if (!closed.contains(neighbour)) {
                    double neighbourG = current.getG()+this.worldInfo.getDistance(neighbour, current.getId())/1000;
                    if (!(me instanceof PoliceForce)) {
                        if (this.pathhelper.unpassable.get(current.getId()) == neighbour) {
                            //logger.debug("----- need to change path -----");
                            neighbourG *= 100000;
                        }
                        if (this.pathhelper.getAllBadEntrance().contains(neighbour)) {
                            neighbourG*=1000;
                        }
                    }
                    if (!iscon(neighbourNode,open)){
                        neighbourNode.setParent(current);
                        neighbourNode.setH(this.worldInfo.getDistance(neighbour, destination)/1000);
                        neighbourNode.setG(neighbourG);
                        neighbourNode.setCost(neighbourNode.getH() + neighbourG);
                        open.add(neighbourNode);
                    }
                    else{
                        if (neighbourNode.getG() > neighbourG) {
                            neighbourNode.setParent(current);
                            neighbourNode.setG(neighbourG);  
                            neighbourNode.setCost(neighbourNode.getH() + neighbourG);                      
                        }                             
                    }
                }
            }
            
        }
        
        if (found) {
            return getPath(destinationNode);
        } else {
            return new ArrayList<EntityID>();
        }
    }
    
    public boolean iscon(Node node , Set<Node> open){
    	if(open.isEmpty())
    		return false;
    	for(Node node1:open){
    		if(node.getId()==node1.getId())
    			return true;
    	}
    	return false;
    }

    private List<EntityID> getPath(Node destinationNode) {
        
        List<EntityID> path = new LinkedList<EntityID>();            
        Node current=destinationNode;
        path.add(current.getId());
        while (current.getParent() != null) {
            path.add(0,current.getParent().getId());
            current = current.getParent();
        }           
        return path;
    }

    

}

