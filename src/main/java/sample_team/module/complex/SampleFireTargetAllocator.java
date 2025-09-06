package sample_team.module.complex;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.complex.FireTargetAllocator;
import java.util.HashMap;
import java.util.Map;
import rescuecore2.worldmodel.EntityID;

public class SampleFireTargetAllocator extends FireTargetAllocator {

  public SampleFireTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
  }


  @Override
  public SampleFireTargetAllocator resume(PrecomputeData precomputeData) {
    super.resume(precomputeData);
    return this;
  }


  @Override
  public SampleFireTargetAllocator preparate() {
    super.preparate();
    return this;
  }


  @Override
  public Map<EntityID, EntityID> getResult() {
    return new HashMap<>();
  }


  @Override
  public SampleFireTargetAllocator calc() {
    return this;
  }


  @Override
  public SampleFireTargetAllocator updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
    return this;
  }
}