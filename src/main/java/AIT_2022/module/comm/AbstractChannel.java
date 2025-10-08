package AIT_2022.module.comm;

import adf.core.agent.communication.standard.bundle.*;
import adf.core.agent.info.*;
import java.util.*;

import static java.util.Comparator.*;

public abstract class AbstractChannel implements Channel {

  protected List<StandardMessage> messages = new LinkedList<>();
  protected final int[] numbers;
  protected final boolean radio;

  protected AbstractChannel(int[] numbers, boolean radio) {
    this.numbers = numbers;
    this.radio = radio;
  }

  @Override
  public final void addWithFilter(StandardMessage message) {
    if (this.applyFilter(message)) {
      this.messages.add(message);
    }
  }

  @Override
  public void sort() {
    final Comparator<StandardMessage> comparator =
        comparing(StandardMessage::getSendingPriority).reversed();
    this.messages.sort(comparator);

    //Set<String> set = new HashSet<>();
    //this.messages.removeIf(m -> !set.add(m.getCheckKey()));
  }

  @Override
  public final List<StandardMessage> getMessages() {
    return this.messages;
  }

  @Override
  public final int[] getNumbers() {
    return this.numbers;
  }

  protected boolean applyFilter(StandardMessage message) {
    return message.isRadio() == this.radio;
  }
}
