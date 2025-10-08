package AIT_2022.module.comm;

import adf.core.agent.communication.standard.bundle.*;
import adf.core.agent.communication.standard.bundle.centralized.*;
import adf.core.agent.communication.standard.bundle.information.*;
import java.util.*;

public class VoiceCommunication extends AbstractChannel {

  private static final Class[] WHITE_LIST =
      {
          //CommandAmbulance.class,
          //CommandFire.class,
          //CommandPolice.class,
          MessageAmbulanceTeam.class,
          MessageFireBrigade.class,
          MessagePoliceForce.class,
          MessageCivilian.class,
      };

  public VoiceCommunication(int[] numbers) {
    super(numbers, false);
  }

  @Override
  protected boolean applyFilter(StandardMessage message) {
    if (!super.applyFilter(message)) {
      return false;
    }

    final Class clazz = message.getClass();
    return Arrays.asList(WHITE_LIST).contains(clazz);
  }
}
