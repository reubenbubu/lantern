package shop.orders;

/** Sums the prices of an order's lines; mentions AI only in this comment for human readers. */
public class Clean {

  int total(int[] prices) {
    int sum = 0;
    for (int price : prices) {
      sum += price;
    }
    return sum;
  }
}
