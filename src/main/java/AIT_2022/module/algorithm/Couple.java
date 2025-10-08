package AIT_2022.module.algorithm;

public class Couple<T> {

  public final T elem1;
  public final T elem2;

  public Couple(T elem1, T elem2) {
    this.elem1 = elem1;
    this.elem2 = elem2;
  }

  @Override
  public boolean equals(Object object) {
    if (object == null) {
      return false;
    }
    if (!Couple.class.isInstance(object)) {
      return false;
    }

    final Couple other = (Couple) object;
    if (this.elem1 == null && other.elem1 != null) {
      return false;
    }
    if (this.elem2 == null && other.elem2 != null) {
      return false;
    }
    if (this.elem1 != null && !this.elem1.equals(other.elem1)) {
      return false;
    }
    if (this.elem2 != null && !this.elem2.equals(other.elem2)) {
      return false;
    }
    return true;
  }

  @Override
  public int hashCode() {
    int hash1 = this.elem1 == null ? 0 : this.elem1.hashCode();
    int hash2 = this.elem2 == null ? 0 : this.elem2.hashCode();
    return hash1 ^ hash2;
  }
}
