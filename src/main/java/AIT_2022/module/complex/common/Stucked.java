package AIT_2022.module.complex.common;

import java.util.Set;

import org.locationtech.jts.geom.*;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.worldmodel.EntityID;

public class Stucked {

  private boolean result = false;
  private Blockade blockade = null;

  public Stucked(Blockade b, double x, double y) {
    this.blockade = b;
    this.result = this.isStucked(b, x, y);
  }

  public Stucked(Set<Blockade> bs, double x, double y) {
    if (bs == null) {
      return;
    }
    for (Blockade b : bs) {
      if (this.isStucked(b, x, y)) {
        this.blockade = b;
        this.result = true;
        break;
      }
    }
  }

  private boolean isStucked(Blockade b, double x, double y) {
    if (b == null) {
      return false;
    }
    int apexs[] = b.getApexes();
    Coordinate[] cd = new Coordinate[apexs.length / 2 + 1];
    for (int i = 0; i < apexs.length / 2; i++) {
      cd[i] = new Coordinate(apexs[i * 2], apexs[i * 2 + 1]);
    }
    cd[(int) (apexs.length / 2)] = cd[0];
    GeometryFactory gf = new GeometryFactory();
    Geometry gm = gf.createPolygon(cd);
    Coordinate a = new Coordinate(x, y);
    Point p = gf.createPoint(a);
    return gm.contains(p);
  }

  public boolean isStucked() {
    return this.result;
  }

  public Blockade getBlockade() {
    return this.blockade == null ? null : this.blockade;
  }

  public EntityID getBlockadeID() {
    return this.blockade == null ? null : this.blockade.getID();
  }
}