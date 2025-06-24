package com.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class StringUtilsTest {
    
    private StringUtils stringUtils;
    
    @BeforeEach
    public void setUp() {
        stringUtils = new StringUtils();
    }
    
    @Test
    public void testReverse() {
        assertEquals("olleh", stringUtils.reverse("hello"));
        assertEquals("", stringUtils.reverse(""));
        assertEquals("a", stringUtils.reverse("a"));
        assertNull(stringUtils.reverse(null));
    }
    
    @Test
    public void testIsPalindrome() {
        assertTrue(stringUtils.isPalindrome("racecar"));
        assertTrue(stringUtils.isPalindrome("A man a plan a canal Panama"));
        assertFalse(stringUtils.isPalindrome("hello"));
        assertFalse(stringUtils.isPalindrome(null));
    }
    
    @Test
    public void testCountWords() {
        assertEquals(3, stringUtils.countWords("hello world test"));
        assertEquals(1, stringUtils.countWords("hello"));
        assertEquals(0, stringUtils.countWords(""));
        assertEquals(0, stringUtils.countWords(null));
        assertEquals(2, stringUtils.countWords("  hello   world  "));
    }
}
