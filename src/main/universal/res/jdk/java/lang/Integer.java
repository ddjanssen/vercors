package java.lang;

class Integer {
    //@ ensures intValue() == x;
    public Integer(int x) {
        //@ assume false;
    }

    private Integer() {
        //@ assume false;
    }

    /*@
    ghost
    pure public int intValue();
     */

    //@ ensures \result != null;
    public String toString() {
       //@ assume false;
        return null;
    }
}