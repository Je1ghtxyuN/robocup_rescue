package SEU.world;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;
import java.util.*;



public class SEUNeighborhood {

	//Static member  these will be initialized in initializer //
	private int NeighborhoodID;
	private List<EntityID> SpecialBuildingIDs;
	private List<Building> NeighborhoodBuildings;
	private List<Building> NeighborhoodEdges;
	private List<EntityID> NeighborhoodRoads;
	private Point2D NeighborhoodCenter;

	//Dynamic member these will be update in tool//
	private List<Building> BurningBuildings;
	private List<Building> SafeBuildings;
	private int TrappedCivilianAmount;											//等待救援的市民数量
	private Point2D FireDirection;
	private Point2D FireCenter;
	private int BurningScore;
	private boolean Onfire=false;

	public SEUNeighborhood()
	{
		this.NeighborhoodID=-1;
		this.SpecialBuildingIDs=new ArrayList<>();
		this.BurningBuildings=new ArrayList<>();
		this.SafeBuildings=new ArrayList<>();
		this.NeighborhoodBuildings=new ArrayList<>();
		this.NeighborhoodEdges=new ArrayList<>();
		this.TrappedCivilianAmount=0;
		this.NeighborhoodCenter=new Point2D(-1,-1);
		this.FireCenter=new Point2D(-1,-1);
		this.FireDirection=new Point2D(-1,-1);
	}


	public void SetID(int i){
		this.NeighborhoodID=i;
	}
	public void SetSpecialIDs(List<EntityID> SpecialBuildingIDs)
	{
		this.SpecialBuildingIDs=SpecialBuildingIDs;
	}
	public void SetNeighborhoodBuildings(List<Building> NeighborhoodBuildings)
	{
		this.NeighborhoodBuildings=NeighborhoodBuildings;
	}
	public void SetBurningBuildings(List<Building> BurningBuildings)
	{
		this.BurningBuildings=BurningBuildings;
	}
	public void SetSafeBuildings(List<Building> SafeBuildings)
	{
		this.SafeBuildings=SafeBuildings;
	}
	public void SetEdges(List<Building> NeighborhoodEdges)
	{
		this.NeighborhoodEdges=NeighborhoodEdges;
	}
	public void SetCivilianAmount(int i)
	{
		this.TrappedCivilianAmount=i;
	}
	public void SetCenter(Point2D Center)
	{
		this.NeighborhoodCenter=Center;
	}
	public void SetFireDirection(Point2D Direction)
	{
		this.FireDirection=Direction;
	}
	public void SetFireCenter(Point2D FireCenter)
	{
		this.FireCenter=FireCenter;
	}


	public List<EntityID> GetRoads()
	{
		return this.NeighborhoodRoads;
	}
	public void SetRoads(List<EntityID> roads)
	{
		this.NeighborhoodRoads=roads;
	}
	public int GetID(){
		return this.NeighborhoodID;
	}
	public List<Building> GetBuildings()
	{
		return this.NeighborhoodBuildings;
	}
	public List<EntityID> GetSpecialIDs()
	{
		return this.SpecialBuildingIDs;
	}
	public List<Building> GetBurningBuildings()
	{
		return this.BurningBuildings;
	}
	public List<Building> GetSafeBuildings()
	{
		return this.SafeBuildings;
	}
	public List<Building> GetEdges()
	{
		return this.NeighborhoodEdges;
	}
	public int GetCivilianAmount()
	{
		return this.TrappedCivilianAmount;
	}
	public Point2D GetCenter()
	{
		return this.NeighborhoodCenter;
	}
	public Point2D GetFireDirection()
	{
		return this.FireDirection;
	}
	public Point2D GetFireCenter()
	{

		return this.FireCenter;
	}
	public int GetBurningScore()
	{
		int score=0;
		for(Building building : this.NeighborhoodBuildings)
		{
			if(building.isFierynessDefined())
				score+=building.getFieryness()*building.getTotalArea();
		}
		return score;

	}
	public boolean isOnFire()
	{
		int burning=0;
		int total=0;
		for(Building building : this.NeighborhoodBuildings)
		{
			if(building.isOnFire())
			{
				return true;
			}
		}
		return false;
	}
	public int GetFireStep()
	{
		int burning=0;
		int total=0;
		for(Building building : this.NeighborhoodBuildings)
		{
			total+=8*building.getTotalArea();
			if(building.isFierynessDefined())
			{
				burning+=building.getFieryness()*building.getTotalArea();
			}
		}
		if(burning/total>=0.5)
			return 2;
		else if(burning/total>=0.25)
			return 1;
		return 0;
	}


	@Override
	public String toString() {
		String s = "";
		s += "ID: " + this.NeighborhoodID + "\n";
		s += "Buildings: ";
		for (Building building : this.NeighborhoodBuildings) s += building.getID().toString() + ",";
		s += "\n";
		s += "Edges: ";
		for (Building building : this.NeighborhoodEdges) s += building.getID().toString() + ",";
		s += "\n";
		s += "Roads: ";
		for (EntityID id : this.NeighborhoodRoads) s += id.toString() + ",";
		s += "\n";
		s += "Center: " + this.NeighborhoodCenter.toString() + "\n";
		return s;
	}
}
