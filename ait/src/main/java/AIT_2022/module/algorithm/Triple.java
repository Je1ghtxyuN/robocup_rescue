package AIT_2022.module.algorithm;

public class Triple<T> {

  public final T elem1;
  public final T elem2;
  public final T elem3;

  public Triple(T elem1, T elem2, T elem3) {
    this.elem1 = elem1;
    this.elem2 = elem2;
    this.elem3 = elem3;
  }

  @Override
  public boolean equals(Object object) {
    if (object == null) {
      return false;
    }
    if (!Triple.class.isInstance(object)) {
      return false;
    }

    final Triple other = (Triple) object;
    if (this.elem1 == null && other.elem1 != null) {
      return false;
    }
    if (this.elem2 == null && other.elem2 != null) {
      return false;
    }
    if (this.elem3 == null && other.elem3 != null) {
      return false;
    }
    if (this.elem1 != null && !this.elem1.equals(other.elem1)) {
      return false;
    }
    if (this.elem2 != null && !this.elem2.equals(other.elem2)) {
      return false;
    }
    if (this.elem3 != null && !this.elem3.equals(other.elem3)) {
      return false;
    }
    return true;
  }

  @Override
  public int hashCode() {
    int hash1 = this.elem1 == null ? 0 : this.elem1.hashCode();
    int hash2 = this.elem2 == null ? 0 : this.elem2.hashCode();
    int hash3 = this.elem3 == null ? 0 : this.elem3.hashCode();
    return hash1 ^ hash2 ^ hash3;
  }
}
