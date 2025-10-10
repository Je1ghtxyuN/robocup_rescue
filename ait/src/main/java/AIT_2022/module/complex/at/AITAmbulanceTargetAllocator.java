package AIT_2022.module.complex.at;

import java.util.*;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.information.MessageAmbulanceTeam;
import adf.core.component.communication.CommunicationMessage;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.complex.AmbulanceTargetAllocator;
import rescuecore2.standard.entities.*;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

public class AITAmbulanceTargetAllocator extends AmbulanceTargetAllocator {

  //最適な避難所の情報が入ったメッセージ
  private final int OPT_REFUGE = 7;

  private final int REDIRECTION_WAITINGLISTSIZE = 3;

  private final double AGENT_CAN_MOVE = 7000.0;

  public AITAmbulanceTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
  }

  @Override
  public AmbulanceTargetAllocator resume(PrecomputeData precomputeData) {
    super.resume(precomputeData);
    return this;
  }

  @Override
  public AmbulanceTargetAllocator preparate() {
    super.preparate();
    return this;
  }

  @Override
  public AmbulanceTargetAllocator updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
    if (this.getCountUpdateInfo() >= 2) {
      return this;
    }

    //AmbulanceCentreが複数あった場合稼働するのは1つ
    Collection<EntityID> ambulanceCentres = this.worldInfo.getEntityIDsOfType(StandardEntityURN.AMBULANCE_CENTRE);
    List<EntityID> atCts = new ArrayList<>(ambulanceCentres);
    Collections.sort(atCts, Comparator.comparing(EntityID::getValue));
    if (atCts.size() > 1) {
      EntityID id = atCts.get(0);
      if (id.equals(this.agentInfo.getID())) {
        //AmbulanceTeamの避難所決定を通信を使用し最適な決定にする処理
        this.detectOptRefuge(messageManager);
      }
    }

    return this;
  }

  @Override
  public Map<EntityID, EntityID> getResult() {
    return new HashMap<>();
  }

  @Override
  public AmbulanceTargetAllocator calc() {
    return this;
  }

  /**
   * AmbulanceTeamからメッセージを受け取り，避難所の情報を確認し，
   * 最適な避難所をAmbulanceTeamに返す
   */
  private void detectOptRefuge(MessageManager mm) {
    // MessageAmbulance(ACTION_LOAD)を受け取った場合の処理
    final List<CommunicationMessage> msgAt = mm.getReceivedMessageList(MessageAmbulanceTeam.class);
    for (CommunicationMessage message : msgAt) {
      MessageAmbulanceTeam msg = (MessageAmbulanceTeam) message;
      if (msg.getAction() == OPT_REFUGE) {
        EntityID agentID = msg.getAgentID();
        AmbulanceTeam agent = (AmbulanceTeam) this.worldInfo.getEntity(agentID);
        EntityID refugeID = msg.getTargetID();
        Refuge refuge = (Refuge) this.worldInfo.getEntity(refugeID);
        //行き先Refugeの変更をする場合
        if (REDIRECTION_WAITINGLISTSIZE < refuge.getWaitingListSize()) {
          EntityID optRefugeID = this.getOptRefugeID(agentID);
          if (optRefugeID == null || optRefugeID.equals(refugeID)) return;
          mm.addMessage(new MessageAmbulanceTeam(true, agent, OPT_REFUGE, optRefugeID));
          //System.out.println("[Cen" + this.agentInfo.getID() + " send📨]" + optRefugeID);
        }
      }
    }
  }

  /**
   * 最適な避難所を返す
   * @param agentID 市民を運ぶAmbulanceTeamのID
   * @return 最適なRefugeID
   */
  private EntityID getOptRefugeID(EntityID agentID) {
    //RefugeIDとその評価値(大きい方が良い値)
    Map<EntityID, Double> refuges = new HashMap<>();
    //refugesを更新
    for (EntityID id : this.worldInfo.getEntityIDsOfType(StandardEntityURN.REFUGE)) {
      Refuge refuge = (Refuge) this.worldInfo.getEntity(id);
      int numAvailableBed = refuge.getBedCapacity() - refuge.getOccupiedBeds();
      double distance = 1 / (this.worldInfo.getDistance(agentID, id) / AGENT_CAN_MOVE);
      refuges.put(id, distance + numAvailableBed);
    }
    //System.out.println("Eva: " + refuges);
    //評価値が1番大きい避難所を見つける
    Double maxEva = Double.MIN_VALUE;
    EntityID reID = null;
    for (Map.Entry<EntityID, Double> entry : refuges.entrySet()) {
      if (maxEva < entry.getValue()) {
        maxEva = entry.getValue();
        reID = entry.getKey();
      }
    }
    return reID;
  }
}
