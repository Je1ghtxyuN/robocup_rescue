package AIT_2022.module.complex.common;

import rescuecore2.standard.entities.Human;

public class LifeXpectancy {

  private Human h;
  private final double D_NOISE = 0.1;
  private final double D_K_B = 0.00025;
  private final double D_K_C = 0.00035;
  // private final double D_K_F = 0.00025;
  private final double D_L_B = 0.01;
  private final double D_L_C = 0.01;
  // private final double D_L_F = 0.03;

  public LifeXpectancy(Human h) {
    this.h = h;
  }

  public LifeXpectancy() {
    this.h = null;
  }

  public int getLifeXpectancy() {
    return this.calcLifeXpectancy(h);
  }

  public int getLifeXpectancy(int hp, int damage) {
    return this.calcLifeXpectancy(hp, damage);
  }

  private int calcLifeXpectancy(Human h) {
    double hp = h.getHP();
    double damage = h.getDamage();
    int time = 0;
    while (hp > 0) {
      final double d = damage;
      damage += this.calcDamageBury(d);
      damage += this.calcDamageCollapse(d);
      damage += this.calcDamageFire(d);
      hp -= damage;
      time++;
    }
    return time;
  }

  private int calcLifeXpectancy(int n, int m) {
    double hp = n;
    double damage = m;
    int time = 0;
    while (hp > 0) {
      final double d = damage;
      damage += this.calcDamageBury(d);
      damage += this.calcDamageCollapse(d);
      damage += this.calcDamageFire(d);
      hp -= damage;
      time++;
    }
    return time;
  }

  private double calcDamageBury(double d) {
    return (D_K_B * d * d) + D_L_B + D_NOISE;
  }

  private double calcDamageCollapse(double d) {
    return (D_K_C * d * d) + D_L_C + D_NOISE;
  }

  private double calcDamageFire(double d) {
    // return (D_K_F * d * d) + D_L_F + D_NOISE;
    return 0;
  }

}