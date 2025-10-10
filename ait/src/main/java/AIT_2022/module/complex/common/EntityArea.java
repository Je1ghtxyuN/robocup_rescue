package AIT_2022.module.complex.common;

import org.locationtech.jts.geom.*;

import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Road;

public class EntityArea {

  private double result = 0.0;

  public EntityArea(Area a) {
    if (a == null) {
      return;
    }
    this.calcMenseki(a.getApexList());
  }

  public EntityArea(Building b) {
    if (b == null) {
      return;
    }
    this.calcMenseki(b.getApexList());
  }

  public EntityArea(Road r) {
    if (r == null) {
      return;
    }
    this.calcMenseki(r.getApexList());
  }

  public EntityArea(Blockade b) {
    if (b == null) {
      return;
    }
    this.calcMenseki(b.getApexes());
  }

  public double getResult() {
    return this.result;
  }

  private void calcMenseki(int[] apexs) {
    Coordinate[] cd = new Coordinate[apexs.length / 2 + 1];
    for (int i = 0; i < apexs.length / 2; i++) {
      cd[i] = new Coordinate(apexs[i * 2], apexs[i * 2 + 1]);
    }
    cd[(int) (apexs.length / 2)] = cd[0];
    GeometryFactory gf = new GeometryFactory();
    Geometry gm = gf.createPolygon(cd);
    this.result = gm.getArea();
  }
}
