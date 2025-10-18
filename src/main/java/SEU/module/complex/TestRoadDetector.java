package SEU.module.complex;


import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.MessageUtil;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.communication.standard.bundle.information.MessageAmbulanceTeam;
import adf.core.agent.communication.standard.bundle.information.MessageFireBrigade;
import adf.core.agent.communication.standard.bundle.information.MessagePoliceForce;
import adf.core.agent.communication.standard.bundle.information.MessageRoad;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.module.complex.RoadDetector;
import adf.core.debug.DefaultLogger;
import adf.impl.module.algorithm.AStarPathPlanning;
import org.apache.log4j.Logger;
import rescuecore2.messages.Command;
import rescuecore2.standard.entities.*;
import rescuecore2.standard.messages.AKSpeak;
import rescuecore2.worldmodel.EntityID;

import java.util.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

public class TestRoadDetector extends RoadDetector {

	int count=0;
	private Logger logger;

	private Set<EntityID> targetAreas; //目标道路，是所有堵塞的区域
	private Set<EntityID> priorityRoads; //道路优先级
	private Set<EntityID> refugeLocation;
	private Set<EntityID> openedAreas = new HashSet<>();

	private PathPlanning pathPlanning;

	private Clustering clustering;

	private EntityID result;

	private Set<Integer> historyClusters = new HashSet<>();

	private EntityID longTermTarget;

	private boolean isLongTermSearch = false;

	public TestRoadDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData)
	{
		super(ai, wi, si, moduleManager, developData);
		logger = DefaultLogger.getLogger(agentInfo.me());
		this.pathPlanning = moduleManager.getModule(
				"SampleRoadDetector.PathPlanning",
				"adf.impl.module.algorithm.DijkstraPathPlanning");
		this.clustering = moduleManager.getModule("SampleRoadDetector.Clustering",
				"adf.impl.module.algorithm.KMeansClustering");
		registerModule(this.clustering);
		registerModule(this.pathPlanning);
		this.result = null;
		longTermTarget = null;
	}


	@Override
	public RoadDetector calc()
	{
		//在目标区域中，直接清除目标
		//如果不在目标区域中，先更新优先级道路
		//如果优先级道路不为空，优先清理优先级道路
		//如果优先级道路为空，则直接开始清除目标区域
		logger.debug("priority roads" + priorityRoads);
		logger.debug("target areas" + targetAreas);
		logger.debug("opened areas" + openedAreas);

		EntityID positionID = this.agentInfo.getPosition();//获取当前位置
		if (positionID.equals(longTermTarget)) {
			isLongTermSearch = false;
		}
		if (longTermTarget != null && isLongTermSearch) {
			this.pathPlanning.setFrom(positionID);
			this.pathPlanning.setDestination(longTermTarget);
			List<EntityID> path = this.pathPlanning.calc().getResult();
			if (path != null && !path.isEmpty())
			{
				this.result = path.get(path.size() - 1);

			}
			return this;
		}


		int MyHP=((Human)this.agentInfo.me()).getHP();//获取当前血量
		if(MyHP <= 2000)
		{
			for (StandardEntity e : this.worldInfo.getEntitiesOfType(REFUGE))
			{
				EntityID id=e.getID();
				Set<EntityID> RefugeLocation=new HashSet<>();
				RefugeLocation.add(id);
				this.pathPlanning.setFrom(positionID);
				this.pathPlanning.setDestination(RefugeLocation);
				List<EntityID> path = this.pathPlanning.calc().getResult();
				if (path != null && !path.isEmpty())
				{
					this.result = path.get(path.size() - 1);

				}
				return this;
			}
		}//回家
		if(MyHP <= 5000 )
		{
			this.result=null;
			return this;
		}//巡逻

		//logger.debug("The count="+this.count);

		if(this.result!= null)
			this.count++;
		if(count == 6 ){//如果在这个地方停留6次，则强制更换工作地方
			this.count=0;
			targetAreas.remove(positionID);
			this.pathPlanning.setFrom(positionID);
			this.pathPlanning.setDestination(this.targetAreas);
			List<EntityID> path = this.pathPlanning.calc().getResult();
			if (path != null && !path.isEmpty())
			{
				//logger.debug("0.Select the path:"+path);
				this.result = path.get(path.size() - 1);
			}
			//logger.debug("0.Selected Target: " + this.result);
			return this;
		}
		if (this.result == null)
		{
			this.count=0;
			//logger.debug("NowPosition:"+positionID);

			if (this.targetAreas.contains(positionID))
			{
				this.result = positionID;
				//logger.debug("1.Selected Target: " + this.result);
				return this;
			}//如果警察在目标区域内直接结束即可

			//将优先级队列中没有堵塞的道路清除
			List<EntityID> removeList = new ArrayList<>(this.priorityRoads.size()); //remove队列的长度等于优先级队列的长度
			for (EntityID id : this.priorityRoads)
			{
				if (!this.targetAreas.contains(id))
				{
					removeList.add(id);
				}
			}//如果该优先级的道路在目标区域中，加入到remove队列中

			removeList.forEach(this.priorityRoads::remove);//清除PriorityRoads列表中在removeList包含的全部元素
			//即删除在目标区域的道路，即代表该路上的路障已经被清除

			//如果优先级道路还存在

			//logger.debug("***priorityRoads"+this.priorityRoads);
			//logger.debug("***targetAreas"+this.targetAreas);

			if (!this.refugeLocation.isEmpty()) {
				this.pathPlanning.setFrom(positionID);//当前位置
				this.pathPlanning.setDestination(this.refugeLocation);//目标区域

				List<EntityID> path = this.pathPlanning.calc().getResult();
				if (path != null && !path.isEmpty())
				{
					this.result = path.get(path.size() - 1);
				}
				return this;
			}
			if (!this.priorityRoads.isEmpty())
			{
				//排序

				//logger.debug("1.***priorityRoads"+this.priorityRoads);
				//logger.debug("1.***targetAreas"+this.targetAreas);

				this.pathPlanning.setFrom(positionID);//当前位置
				this.pathPlanning.setDestination(this.priorityRoads);//目标区域

				List<EntityID> path = this.pathPlanning.calc().getResult();
				if (path != null && !path.isEmpty())
				{
					//logger.debug("2.1 Select the path:"+path);
					this.result = path.get(path.size() - 1);
				}
				else {
					this.pathPlanning.setFrom(positionID);
					this.pathPlanning.setDestination(this.targetAreas);
					path = this.pathPlanning.calc().getResult();
					if (path != null && !path.isEmpty())
					{
						//logger.debug("2.2 Select the path:"+path);
						this.result = path.get(path.size() - 1);
					}
				}
				//logger.debug("2.Selected Target: " + this.result);
				return this;
			}/* else {
				logger.debug("start searching randomly");
				List<Integer> allClusters = new ArrayList<>();
				for (int i = 0; i < this.clustering.getClusterNumber(); i++) {
					allClusters.add(i);
				}
				allClusters.removeAll(historyClusters);
				Collection<EntityID> allBuildings = new ArrayList<>();
				if (!allClusters.isEmpty()) {
					for (int i : allClusters) {
						for (EntityID e : this.clustering.getClusterEntityIDs(i)) {
							if (this.worldInfo.getEntity(e) instanceof Building) {
								allBuildings.add(e);
							}
						}
					}
				} else {
					allBuildings.addAll(this.worldInfo.getEntityIDsOfType(BUILDING));
				}
				allBuildings.removeAll(openedAreas);
				List<EntityID> allBuildingsWithSequence = new ArrayList<>(allBuildings);
				Collection<EntityID> randomTarget = new ArrayList<>();
				longTermTarget = allBuildingsWithSequence.get((int) (Math.random() * allBuildingsWithSequence.size()));
				randomTarget.add(longTermTarget);
				this.pathPlanning.setFrom(positionID);
				this.pathPlanning.setDestination(randomTarget);
				List<EntityID> path = this.pathPlanning.calc().getResult();
				if (path != null && !path.isEmpty()) {
					//logger.debug("3.Select the path:"+path);
					this.result = path.get(path.size() - 1);
				}
				//isLongTermSearch = true;
			}*/
			if (!targetAreas.isEmpty()) {
				//logger.debug("2.***priorityRoads"+this.priorityRoads);
				//logger.debug("2.***targetAreas"+this.targetAreas);
				this.pathPlanning.setFrom(positionID);
				this.pathPlanning.setDestination(this.targetAreas);
				List<EntityID> path = this.pathPlanning.calc().getResult();
				if (path != null && !path.isEmpty()) {
					//logger.debug("3.Select the path:"+path);
					this.result = path.get(path.size() - 1);
				}
			} else {
				logger.debug("start searching randomly");
				List<Integer> allClusters = new ArrayList<>();
				for (int i = 0; i < this.clustering.getClusterNumber(); i++) {
					allClusters.add(i);
				}
				allClusters.removeAll(historyClusters);
				Collection<EntityID> allBuildings = new ArrayList<>();
				if (!allClusters.isEmpty()) {
					for (int i : allClusters) {
						for (EntityID e : this.clustering.getClusterEntityIDs(i)) {
							if (this.worldInfo.getEntity(e) instanceof Building) {
								allBuildings.add(e);
							}
						}
					}
				} else {
					allBuildings.addAll(this.worldInfo.getEntityIDsOfType(BUILDING));
				}
				allBuildings.removeAll(openedAreas);
                List<EntityID> allBuildingsWithSequence = new ArrayList<>(allBuildings);
				Collection<EntityID> randomTarget = new ArrayList<>();
				longTermTarget = allBuildingsWithSequence.get((int) (Math.random() * allBuildingsWithSequence.size()));
				randomTarget.add(longTermTarget);
				this.pathPlanning.setFrom(positionID);
				this.pathPlanning.setDestination(randomTarget);
				List<EntityID> path = this.pathPlanning.calc().getResult();
				if (path != null && !path.isEmpty()) {
					//logger.debug("3.Select the path:"+path);
					this.result = path.get(path.size() - 1);
				}
				isLongTermSearch = true;
			}
		}
		//logger.debug("3.Selected Target: " + this.result);
		return this;
	}

	@Override
	public EntityID getTarget()
	{
		return this.result;
	}

	@Override
	public RoadDetector updateInfo(MessageManager messageManager) {

		if(agentInfo.getTime()==1)
		{
			this.priorityRoads = new HashSet<>();
			this.targetAreas =new HashSet<>();
			this.refugeLocation = new HashSet<>();
			for (StandardEntity e : this.worldInfo.getEntitiesOfType(REFUGE)) {
				for (EntityID id : ((Building) e).getNeighbours()) {
					StandardEntity neighbour = this.worldInfo.getEntity(id);
					if (neighbour instanceof Road) {
						this.priorityRoads.add(id);
						this.targetAreas.add(id);
						if (((Road) neighbour).isBlockadesDefined() && !((Road) neighbour).getBlockades().isEmpty()) {
							this.refugeLocation.add(id);
						}
						for(EntityID neighbourID : ((Road) neighbour).getNeighbours()){
							StandardEntity neighbour_neighbour = this.worldInfo.getEntity(neighbourID);
							if(neighbour_neighbour instanceof Road)
							{
								this.priorityRoads.add(id);
								this.targetAreas.add(id);
							}
						}
					}
				}
			}//优先级队列
		}
		else
		{
			this.targetAreas = new HashSet<>();
			for (StandardEntity e : this.worldInfo.getEntitiesOfType(REFUGE,GAS_STATION,BUILDING)) {
				for (EntityID id : ((Building) e).getNeighbours()) {
					StandardEntity neighbour = this.worldInfo.getEntity(id);
					if (neighbour instanceof Road) {
						if (((Road) neighbour).isBlockadesDefined() && !((Road) neighbour).getBlockades().isEmpty())
						{
							this.targetAreas.add(id);
						}
						for(EntityID neighbourID : ((Road) neighbour).getNeighbours()){
							StandardEntity neighbour_neighbour = this.worldInfo.getEntity(neighbourID);
							if(neighbour_neighbour instanceof Road)
							{
								if (((Road) neighbour).isBlockadesDefined() && !((Road) neighbour).getBlockades().isEmpty())
								{
									this.targetAreas.add(id);
								}
							}
						}
					}
				}
			}//目标队列
			//for (StandardEntity e : this.worldInfo.getEntitiesOfType(REFUGE)) {
			//	this.targetAreas.add(e.getID());
			//}
			this.priorityRoads = new HashSet<>();
			for (StandardEntity e : this.worldInfo.getEntitiesOfType(REFUGE)) {
				for (EntityID id : ((Building) e).getNeighbours()) {
					StandardEntity neighbour = this.worldInfo.getEntity(id);
					if (neighbour instanceof Road) {
						if (((Road) neighbour).isBlockadesDefined() && !((Road) neighbour).getBlockades().isEmpty())
						{
							this.priorityRoads.add(id);
						}
						for(EntityID neighbourID : ((Road) neighbour).getNeighbours()){
							StandardEntity neighbour_neighbour = this.worldInfo.getEntity(neighbourID);
							if(neighbour_neighbour instanceof Road)
							{
								if (((Road) neighbour).isBlockadesDefined() && !((Road) neighbour).getBlockades().isEmpty())
								{
									this.priorityRoads.add(id);
								}
							}
						}
					}
				}
			}//优先级队列
			for (StandardEntity se : this.worldInfo.getEntitiesOfType(CIVILIAN)) {
				if (isValidHuman(se)) {
					Civilian civilian = (Civilian) se;
					EntityID civilianPositionID = civilian.getPosition();
					StandardEntity minDistanceRefuge = null;
					int minDistance = 1000000;
					for (StandardEntity e : this.worldInfo.getEntitiesOfType(REFUGE)) {
						int currentDistance = this.worldInfo.getDistance(se, e);
						if (currentDistance < minDistance) {
							minDistanceRefuge = e;
							minDistance = currentDistance;
						}
					}
					Collection<EntityID> refugeToGo = new ArrayList<>();
					if (minDistanceRefuge != null) {
						refugeToGo.add(minDistanceRefuge.getID());
					}
					this.pathPlanning.setFrom(civilianPositionID);
					this.pathPlanning.setDestination(refugeToGo);
					List<EntityID> path = this.pathPlanning.calc().getResult();
					this.priorityRoads.addAll(path);
				}
			}
			for (Command command : Objects.requireNonNull(this.agentInfo.getHeard())) {
				if (command instanceof AKSpeak && ((AKSpeak) command).getChannel() == 0 && command.getAgentID() != this.agentInfo.getID()) {
					byte[] receivedData = ((AKSpeak) command).getContent();
					String voiceString = new String(receivedData);
					if ("Help".equalsIgnoreCase(voiceString) || "Ouch".equalsIgnoreCase(voiceString)) {
						int range = this.scenarioInfo.getRawConfig().getIntValue("comms.channels.0.range");
						Collection<StandardEntity> possibleBuildings = this.worldInfo.getObjectsInRange(this.agentInfo.getID(), range);
						for (StandardEntity possibleBuilding : possibleBuildings) {
							if (possibleBuilding instanceof Building) {
								for (EntityID civilianNeighbourID : ((Area) possibleBuilding).getNeighbours()) {
									StandardEntity civilianNeighbour = this.worldInfo.getEntity(civilianNeighbourID);
									if (civilianNeighbour instanceof Road) {
										priorityRoads.add(civilianNeighbourID);
									}
								}
							}
						}
					}
				}
			}
		}



		//logger.debug("TheTime:"+agentInfo.getTime());
		super.updateInfo(messageManager);
		if (this.getCountUpdateInfo() >= 2)
		{
			return this;
		}
		if (this.result != null)
		{


			if (this.agentInfo.getPosition().equals(this.result))
			{
				//logger.debug("我刚刚到达目的地！");
				StandardEntity entity = this.worldInfo.getEntity(this.result);
				if (entity instanceof Building)
				{
					this.result = null;
				}
				else if (entity instanceof Road road)
				{
                    if (!road.isBlockadesDefined() )
					{
						this.targetAreas.remove(this.result);
						this.result = null;
					}//如果该地区没有定义阻塞
					if(road.isBlockadesDefined() && road.getBlockades().isEmpty() )
					{
						this.targetAreas.remove(this.result);
						this.result = null;
					}//如果该地区定义了阻塞，且该地区阻塞不为空
				}
			}
		} //到达目的地之后，重新处理targets




		Set<EntityID> changedEntities = this.worldInfo.getChanged().getChangedEntities();
		for (CommunicationMessage message : messageManager.getReceivedMessageList())//遍历获得的消息
		{
			Class<? extends CommunicationMessage> messageClass = message.getClass();
			if (messageClass == MessageAmbulanceTeam.class)
			{
				this.reflectMessage((MessageAmbulanceTeam) message);

			}//呼应救援队伍
			else if (messageClass == MessageFireBrigade.class)
			{
				this.reflectMessage((MessageFireBrigade) message);

			}//呼应救火队伍
			else if (messageClass == MessageRoad.class)
			{
				this.reflectMessage((MessageRoad) message, changedEntities);

			}//呼应道路信息
			else if (messageClass == MessagePoliceForce.class)
			{
				this.reflectMessage((MessagePoliceForce) message);

			}//呼应其他警察信息
			else if (messageClass == CommandPolice.class)
			{
				this.reflectMessage((CommandPolice) message);

			}
		}

		EntityID currentPositionID = this.agentInfo.getPosition();
		StandardEntity currentPosition = this.worldInfo.getEntity(currentPositionID);
		//openedAreas.add((Area) currentPosition);
		for (StandardEntity e : this.worldInfo.getEntitiesOfType(ROAD)) {
			Road road = (Road) e;
			if (road.isBlockadesDefined() && road.getBlockades().isEmpty()) {
				openedAreas.add(road.getID());
				historyClusters.add(this.clustering.getClusterIndex(road.getID()));
			}
		}
		historyClusters.add(this.clustering.getClusterIndex(this.agentInfo.getPosition()));
		openedAreas.add(this.agentInfo.getPosition());
		for (EntityID e : openedAreas) {
			priorityRoads.remove(e);
			targetAreas.remove(e);
			refugeLocation.remove(e);
		}



		return this;
	}//主要更新已被清理过的区域块

	private void reflectMessage(MessageRoad messageRoad, Collection<EntityID> changedEntities)
	{
		if (messageRoad.isBlockadeDefined() && !changedEntities.contains(messageRoad.getBlockadeID()))
		{
			MessageUtil.reflectMessage(this.worldInfo, messageRoad);
		}
//		if (messageRoad.isPassable())
//		{
//			this.targetAreas.remove(messageRoad.getRoadID());
//		}//如果能通行。将该地区移除
	}

	private void reflectMessage(MessageAmbulanceTeam messageAmbulanceTeam)
	{
		if (messageAmbulanceTeam.getPosition() == null)
		{
			return;
		}
		else if (messageAmbulanceTeam.getAction() == MessageAmbulanceTeam.ACTION_LOAD)
		{
			StandardEntity position = this.worldInfo.getEntity(messageAmbulanceTeam.getPosition());
			if (position instanceof Building)
			{
				((Building) position).getNeighbours().forEach(this.targetAreas::remove);
			}
		}
		else if (messageAmbulanceTeam.getAction() == MessageAmbulanceTeam.ACTION_MOVE)//移动
		{
			if (messageAmbulanceTeam.getTargetID() == null)
			{
				StandardEntity position = this.worldInfo.getEntity(messageAmbulanceTeam.getPosition());
				if(position instanceof Road)
					for (EntityID id : ((Road) position).getNeighbours()) {
						StandardEntity neighbour = this.worldInfo.getEntity(id);
						if (neighbour instanceof Road) {
							if (((Road) neighbour).isBlockadesDefined() && !((Road) neighbour).getBlockades().isEmpty())
								//如果该地区Block被定义，且该Block不为空，加入到目标区域
                                this.targetAreas.add(id);
						}
					}
				return;
			}
			StandardEntity target = this.worldInfo.getEntity(messageAmbulanceTeam.getTargetID());//获取医生的目标
			if (target instanceof Building)//如果医生的目标是建筑物
			{
				for (EntityID id : ((Building) target).getNeighbours())
				{
					StandardEntity neighbour = this.worldInfo.getEntity(id);
					if (neighbour instanceof Road)
					{
						if(((Road) neighbour).isBlockadesDefined() && !((Road) neighbour).getBlockades().isEmpty())
						{
							this.priorityRoads.add(id);
							this.targetAreas.add(id);
						}

					}//如果道路阻塞，或者道路阻塞不为空，加入到优先级队列中
				}
			}//帮助医生清理道路，将医生阻塞的道路加入到优先级队列中
			else if (target instanceof Human human)
			{
                if (human.isPositionDefined())
				{
					StandardEntity position = this.worldInfo.getPosition(human);
					if (position instanceof Building)
					{
						for (EntityID id : ((Building) position).getNeighbours())
						{
							StandardEntity neighbour = this.worldInfo.getEntity(id);
							if (neighbour instanceof Road)
							{
								if(((Road) neighbour).isBlockadesDefined() && !((Road) neighbour).getBlockades().isEmpty())
								{
									this.priorityRoads.add(id);
									this.targetAreas.add(id);
								}
							}//如果被困人员道路被阻塞，清理道路
						}
					}
				}
			}
		}
	}

	private void reflectMessage(MessageFireBrigade messageFireBrigade)//消防队
	{
		if (messageFireBrigade.getTargetID() == null)
		{
			StandardEntity position = this.worldInfo.getEntity(Objects.requireNonNull(messageFireBrigade.getPosition()));
			if(position instanceof Road)
				for (EntityID id : ((Road) position).getNeighbours()) {
					StandardEntity neighbour = this.worldInfo.getEntity(id);
					if (neighbour instanceof Road) {
						if (((Road) neighbour).isBlockadesDefined() && !((Road) neighbour).getBlockades().isEmpty())
						{
                            this.targetAreas.add(id);
                            this.priorityRoads.add(id);
						}//如果该地区Block被定义，且该Block不为空，加入到目标区域

					}
				}
			return;
		}//消防队的目标为空，为防止其被阻碍，跟着去清理目标
		if (messageFireBrigade.getAction() == MessageFireBrigade.ACTION_RESCUE)//正在扑灭火
		{
			StandardEntity position = this.worldInfo.getEntity(Objects.requireNonNull(messageFireBrigade.getPosition()));//消防员的位置
			if (position instanceof Building)
			{
				//this.targetAreas.addAll(((Building) position).getNeighbours());
				((Building) position).getNeighbours().forEach(this.targetAreas::remove);
			}
		}//如果消防员在扑灭火，代表这个地区可以通过，无需清理
		if(messageFireBrigade.getAction() == MessageFireBrigade.ACTION_MOVE)
		{
			StandardEntity target = this.worldInfo.getEntity(messageFireBrigade.getTargetID());//获取消防队的目标
			//logger.debug("FireBrige Target"+target);
			if (target instanceof Building)//如果消防队的目标是建筑物
			{
				for (EntityID id : ((Building) target).getNeighbours())
				{
					StandardEntity neighbour = this.worldInfo.getEntity(id);
					if (neighbour instanceof Road)
					{
						if(((Road) neighbour).isBlockadesDefined() && !((Road) neighbour).getBlockades().isEmpty())
						{
                            this.targetAreas.add(id);
                            this.priorityRoads.add(id);
						}

					}//如果道路阻塞，或者道路阻塞不为空，加入到优先级队列中
				}
			}//帮助消防员清理道路，将医生阻塞的道路加入到优先级队列中
		}
	}

	private void reflectMessage(MessagePoliceForce messagePoliceForce)
	{
		if (messagePoliceForce.getAction() == MessagePoliceForce.ACTION_CLEAR)//如果是在进行清理
		{
			if (messagePoliceForce.getAgentID().getValue() != this.agentInfo.getID().getValue())
			{//响应其他警察的消息
				if (messagePoliceForce.isTargetDefined())//目标如果定义
				{
					EntityID targetID = messagePoliceForce.getTargetID();//获取通信警察的目标消息
					if (targetID == null)
					{
						return;
					}
					StandardEntity entity = this.worldInfo.getEntity(targetID);
					if (entity == null)
					{
						return;
					}

					if (entity instanceof Area)
					{
						this.targetAreas.remove(targetID);//如果该地区为一个警察清理，那么他自己不再清理，确保两两警察之间的目标不一样
						if (this.result != null && this.result.getValue() == targetID.getValue())
						{
							if (this.agentInfo.getID().getValue() < messagePoliceForce.getAgentID().getValue())
							{
								this.result = null;
							}
						}
						//logger.debug("1.该目标已经被分配出去！");
					}
					else if (entity.getStandardURN() == BLOCKADE)//如果封锁
					{
						EntityID position = ((Blockade) entity).getPosition();
						this.targetAreas.remove(position);
						if (this.result != null && this.result.getValue() == position.getValue())
						{
							if (this.agentInfo.getID().getValue() < messagePoliceForce.getAgentID().getValue())
							{
								this.result = null;
							}
						}
						//logger.debug("2.该目标已经被分配出去！");
					}

				}
			}
		}
	}

	private void reflectMessage(CommandPolice commandPolice)
	{
		boolean flag = false;
		if (commandPolice.isToIDDefined() && this.agentInfo.getID().getValue() == Objects.requireNonNull(commandPolice.getToID()).getValue())
		{
			flag = true;
		}
		else if (commandPolice.isBroadcast())
		{
			flag = true;
		}
		if (flag && commandPolice.getAction() == CommandPolice.ACTION_CLEAR)
		{
			if (commandPolice.getTargetID() == null)
			{
				return;
			}
			StandardEntity target = this.worldInfo.getEntity(commandPolice.getTargetID());
			if (target instanceof Area)
			{
				this.targetAreas.add(target.getID());
			}
			else if (target != null && target.getStandardURN() == BLOCKADE) {
                Blockade blockade = (Blockade) target;
                if (blockade.isPositionDefined()) {
                    this.targetAreas.add(blockade.getPosition());
                }
            }
        }
	}

	private boolean isValidHuman(StandardEntity entity) {
		if (entity == null)
			return false;
		if (!(entity instanceof Human target))
			return false;

		if (!target.isHPDefined() || target.getHP() == 0)
			return false;
		if (!target.isPositionDefined())
			return false;
		if (!target.isDamageDefined() || target.getDamage() == 0)
			return false;
		if (!target.isBuriednessDefined())
			return false;

		StandardEntity position = worldInfo.getPosition(target);
		if (position == null)
			return false;

		StandardEntityURN positionURN = position.getStandardURN();
		return positionURN != REFUGE && positionURN != AMBULANCE_TEAM;
	}



}
