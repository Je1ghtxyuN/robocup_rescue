package SEU.world;
/*
 * written by Ou YaMing   
 *   Melkman algorithm
 */


public class SEUPoint {
	private float x;	    //X坐标
	private float y;	    //Y坐标
	private double arCos;	//与P0点的角度
	private int index;		//点到building的索引
	
	
	public SEUPoint(float x, float y, int index)
	{
		this.x = x;
		this.y = y;
		this.index = index;
	}
	public SEUPoint()
	{
		
	}
	public int getIndex()
	{
		return index;
	}
	public float getX() {
		return x;
	}
	public void setX(float x) {
		this.x = x;
	}
	public float getY() {
		return y;
	}
	public void setY(float y) {
		this.y = y;
	}
	public double getArCos() {
		return arCos;
	}
	public void setArCos(double arCos) {
		this.arCos = arCos;
	}
}
