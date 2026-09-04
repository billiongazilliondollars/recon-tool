/** The three loop shapes you use most in Java. */
public class LoopDemo {

    public static void main(String[] args) {
        // Classic for loop: 1 through 5.
        for (int i = 1; i <= 5; i++) {
            System.out.println("i = " + i);
        }

        // While loop: count back down from 3.
        int n = 3;
        while (n > 0) {
            System.out.println("n = " + n);
            n--;
        }

        // Enhanced for loop: walk an array without an index.
        String[] names = {"Ada", "Alan", "Grace"};
        for (String name : names) {
            System.out.println("Hello, " + name);
        }
    }
}
