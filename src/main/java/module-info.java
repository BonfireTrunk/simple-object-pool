/**
 * A lightweight object pool for Java 21+. Its only dependency is SLF4J.
 */
module bonfire.oss.sop {
  requires org.slf4j;

  exports today.bonfire.oss.sop;
  exports today.bonfire.oss.sop.exceptions;
}
