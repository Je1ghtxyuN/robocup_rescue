package AIT_2022.module.comm;

import adf.core.agent.communication.standard.bundle.*;
import adf.core.agent.communication.standard.bundle.information.*;
import adf.core.agent.info.*;
import rescuecore2.standard.entities.StandardEntityURN;

import static rescuecore2.standard.entities.StandardEntityURN.*;

import java.util.*;

public class ChannelAT extends AbstractChannel {

  private static final Class[] WHITE_LIST =
      {
          //CommandAmbulance.class,
          MessageAmbulanceTeam.class,
          //MessageFireBrigade.class,
          //MessagePoliceForce.class,
          MessageCivilian.class,
      };

  private static final Class[] UNION_LIST =
      {
          //MessageReport.class,
          //CommandScout.class
      };

  private AgentInfo ai;

  public ChannelAT(AgentInfo ai, int[] numbers) {
    super(numbers, true);
    this.ai = ai;
  }

  @Override
  protected boolean applyFilter(StandardMessage message) {
    if (!super.applyFilter(message)) {
      return false;
    }

    final StandardEntityURN urn = this.ai.me().getStandardURN();
    final Class clazz = message.getClass();

    final boolean inWhitelist = Arrays.asList(WHITE_LIST).contains(clazz);
    final boolean inUnionlist = Arrays.asList(UNION_LIST).contains(clazz);

    return inWhitelist || inUnionlist && urn == AMBULANCE_TEAM;
  }
}
