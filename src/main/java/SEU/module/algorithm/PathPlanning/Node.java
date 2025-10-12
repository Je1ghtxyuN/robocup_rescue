package SEU.module.algorithm.PathPlanning;

import rescuecore2.worldmodel.EntityID;

public class Node implements Comparable<Node>{  
    private double routeCost;
    private double g;
    private double h;
    private Node parent;
private EntityID point;
        
    public Node(EntityID id) {
        this.point = id;
        this.setParent(null);
        this.routeCost = 0.0;
        this.g = 0;
        this.h = 0;
    }
    public EntityID getId() {
        return point;
    }
    
    public void setG(double g) {
        this.g = g;
    }
    public void setH(double d) {
        this.h = d;
    }
    
    public void setCost(double cost) {
        this.routeCost = cost;
    }
    
    public double getG() {
        return g ;
    }
    public double getH() {
        return h ;
    }
    public double getcost() {
            return routeCost ;
        }
    
    public void setParent(Node parent) {
            this.parent = parent;
        }
    
    public Node getParent() {
            return parent;
    }

    @Override
    public int compareTo(Node route) {
        return (int) (this.getcost() - route.getcost());
    } 
   }
