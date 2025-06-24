package com.example;

public class App {
    public static void main(String[] args) {
        System.out.println("Hello World from Java Maven App!");
        
        Calculator calc = new Calculator();
        System.out.println("2 + 3 = " + calc.add(2, 3));
        System.out.println("5 - 2 = " + calc.subtract(5, 2));
        System.out.println("4 * 3 = " + calc.multiply(4, 3));
        System.out.println("10 / 2 = " + calc.divide(10, 2));
        
        StringUtils utils = new StringUtils();
        System.out.println("Reversed 'hello': " + utils.reverse("hello"));
        System.out.println("'hello' is palindrome: " + utils.isPalindrome("hello"));
        System.out.println("'racecar' is palindrome: " + utils.isPalindrome("racecar"));
    }
}