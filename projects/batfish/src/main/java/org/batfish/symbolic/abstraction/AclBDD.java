package org.batfish.symbolic.abstraction;

import com.microsoft.z3.ArithExpr;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import net.sf.javabdd.BDD;
import net.sf.javabdd.BDDFactory;
import org.batfish.common.BatfishException;
import org.batfish.datamodel.IpAccessList;
import org.batfish.datamodel.IpAccessListLine;
import org.batfish.datamodel.IpProtocol;
import org.batfish.datamodel.IpWildcard;
import org.batfish.datamodel.LineAction;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.SubRange;
import org.batfish.datamodel.TcpFlags;

public class AclBDD {

  IpAccessList _acl;

  BDDPacket _pkt;

  BDDFactory _factory;

  AclBDD(IpAccessList acl) {
    _acl = acl;
    _factory = BDDPacket.factory;
    _pkt = new BDDPacket();
  }

  public BDD compute() {
    return _factory.one();
  }

  /*
   * Does the 32 bit integer match the prefix using lpm?
   */
  public BDD isRelevantFor(Prefix p, BDDInteger i) {
    return TransferBDD.firstBitsEqual(i.getBitvec(), p.getNetworkPrefix(), p.getPrefixLength());
  }

  /*
   * Convert a set of wildcards and a packet field to a symbolic boolean expression
   */
  private BDD computeWildcardMatch(Set<IpWildcard> wcs, BDDInteger field) {
    BDD acc = _factory.zero();
    for (IpWildcard wc : wcs) {
      if (!wc.isPrefix()) {
        throw new BatfishException("ERROR: computeDstWildcards, non sequential mask detected");
      }
      acc = acc.or(isRelevantFor(wc.toPrefix(), field));
    }
    return acc;
  }

  /*
   * Convert a set of ranges and a packet field to a symbolic boolean expression
   */
  private BDD computeValidRange(Set<SubRange> ranges, ArithExpr field) {
    BDD acc = _factory.zero();
    /*
    for (SubRange range : ranges) {
      int start = range.getStart();
      int end = range.getEnd();
      if (start == end) {
        BoolExpr val = mkEq(field, mkInt(start));
        acc = acc.or(val);
      } else {
        BDD val1 = mkGe(field, mkInt(start));
        BDD val2 = mkLe(field, mkInt(end));
        acc = acc.or(val1.and(val2));
      }
    } */
    return acc;
  }

  /*
   * Convert a Tcp flag to a boolean expression on the symbolic packet
   */
  private BDD computeTcpFlags(TcpFlags flags) {
    BDD acc = _factory.one();
    BDD one = _factory.one();
    BDD zero = _factory.zero();
    if (flags.getUseAck()) {
      BDD value = flags.getAck() ? _pkt.getTcpAck() : _pkt.getTcpAck().not();
      acc = acc.and(value);
    }
    if (flags.getUseCwr()) {
      BDD value = flags.getCwr() ? _pkt.getTcpCwr() : _pkt.getTcpCwr().not();
      acc = acc.and(value);
    }
    if (flags.getUseEce()) {
      BDD value = flags.getEce() ? _pkt.getTcpEce() : _pkt.getTcpEce().not();
      acc = acc.and(value);
    }
    if (flags.getUseFin()) {
      BDD value = flags.getFin() ? _pkt.getTcpFin() : _pkt.getTcpFin().not();
      acc = acc.and(value);
    }
    if (flags.getUsePsh()) {
      BDD value = flags.getPsh() ? _pkt.getTcpPsh() : _pkt.getTcpPsh().not();
      acc = acc.and(value);
    }
    if (flags.getUseRst()) {
      BDD value = flags.getRst() ? _pkt.getTcpRst() : _pkt.getTcpRst().not();
      acc = acc.and(value);
    }
    if (flags.getUseSyn()) {
      BDD value = flags.getSyn() ? _pkt.getTcpSyn() : _pkt.getTcpSyn().not();
      acc = acc.and(value);
    }
    if (flags.getUseUrg()) {
      BDD value = flags.getUrg() ? _pkt.getTcpUrg() : _pkt.getTcpUrg().not();
      acc = acc.and(value);
    }
    return acc;
  }

  /*
   * Convert Tcp flags to a boolean expression on the symbolic packet
   */
  private BDD computeTcpFlags(List<TcpFlags> flags) {
    BDD acc = _factory.zero();
    for (TcpFlags fs : flags) {
      acc = acc.or(computeTcpFlags(fs));
    }
    return acc;
  }

  /*
   * Convert a set of ip protocols to a boolean expression on the symbolic packet
   */
  private BDD computeIpProtocols(Set<IpProtocol> ipProtos) {
    BDD acc = _factory.zero();
    for (IpProtocol proto : ipProtos) {
      BDD isValue = _pkt.getIpProtocol().value(proto.number());
      acc = acc.or(isValue);
    }
    return acc;
  }

  /*
   * Convert an Access Control List (ACL) to a symbolic boolean expression.
   * The default action in an ACL is to deny all traffic.
   */
  private BDD computeACL(IpAccessList acl) {
    // Check if there is an ACL first
    if (acl == null) {
      return _factory.one();
    }

    BDD acc = _factory.zero();

    List<IpAccessListLine> lines = new ArrayList<>(acl.getLines());
    Collections.reverse(lines);

    for (IpAccessListLine l : lines) {
      BDD local = null;

      if (l.getDstIps() != null) {
        BDD val = computeWildcardMatch(l.getDstIps(), _pkt.getDstIp());
        val = l.getDstIps().isEmpty() ? _factory.one() : val;
        local = val;
      }

      if (l.getSrcIps() != null) {
        BDD val = computeWildcardMatch(l.getSrcIps(), _pkt.getSrcIp());
        val = l.getDstIps().isEmpty() ? _factory.one() : val;
        local = (local == null ? val : local.and(val));
      }

      if (l.getDscps() != null && !l.getDscps().isEmpty()) {
        throw new BatfishException("detected dscps");
      }

      if (l.getDstPorts() != null) {
        BDD val = _factory.one(); //computeValidRange(l.getDstPorts(), _pkt.getDstPort());
        val = l.getDstPorts().isEmpty() ? _factory.one() : val;
        local = (local == null ? val : local.and(val));
      }

      if (l.getSrcPorts() != null) {
        BDD val = _factory.one(); // computeValidRange(l.getSrcPorts(), _pkt.getSrcPort());
        val = l.getSrcPorts().isEmpty() ? _factory.one() : val;
        local = (local == null ? val : local.and(val));
      }

      if (l.getEcns() != null && !l.getEcns().isEmpty()) {
        throw new BatfishException("detected ecns");
      }

      if (l.getTcpFlags() != null) {
        BDD val = computeTcpFlags(l.getTcpFlags());
        val = l.getTcpFlags().isEmpty() ? _factory.one() : val;
        local = (local == null ? val : local.and(val));
      }

      if (l.getFragmentOffsets() != null && !l.getFragmentOffsets().isEmpty()) {
        throw new BatfishException("detected fragment offsets");
      }

      if (l.getIcmpCodes() != null) {
        BDD val = _factory.one(); //computeValidRange(l.getIcmpCodes(), _pkt.getIcmpCode());
        val = l.getIcmpCodes().isEmpty() ? _factory.one() : val;
        local = (local == null ? val : local.and(val));
      }

      if (l.getIcmpTypes() != null) {
        BDD val = _factory.one(); //computeValidRange(l.getIcmpTypes(), _pkt.getIcmpType());
        val = l.getIcmpTypes().isEmpty() ? _factory.one() : val;
        local = (local == null ? val : local.and(val));
      }

      if (l.getStates() != null && !l.getStates().isEmpty()) {
        throw new BatfishException("detected states");
      }

      if (l.getIpProtocols() != null) {
        BDD val = computeIpProtocols(l.getIpProtocols());
        val = l.getIpProtocols().isEmpty() ? _factory.one() : val;
        local = (local == null ? val : local.and(val));
      }

      if (l.getNotDscps() != null && !l.getNotDscps().isEmpty()) {
        throw new BatfishException("detected NOT dscps");
      }

      if (l.getNotDstIps() != null && !l.getNotDstIps().isEmpty()) {
        throw new BatfishException("detected NOT dst ip");
      }

      if (l.getNotSrcIps() != null && !l.getNotSrcIps().isEmpty()) {
        throw new BatfishException("detected NOT src ip");
      }

      if (l.getNotDstPorts() != null && !l.getNotDstPorts().isEmpty()) {
        throw new BatfishException("detected NOT dst port");
      }

      if (l.getNotSrcPorts() != null && !l.getNotSrcPorts().isEmpty()) {
        throw new BatfishException("detected NOT src port");
      }

      if (l.getNotEcns() != null && !l.getNotEcns().isEmpty()) {
        throw new BatfishException("detected NOT ecns");
      }

      if (l.getNotIcmpCodes() != null && !l.getNotIcmpCodes().isEmpty()) {
        throw new BatfishException("detected NOT icmp codes");
      }

      if (l.getNotIcmpTypes() != null && !l.getNotIcmpTypes().isEmpty()) {
        throw new BatfishException("detected NOT icmp types");
      }

      if (l.getNotFragmentOffsets() != null && !l.getNotFragmentOffsets().isEmpty()) {
        throw new BatfishException("detected NOT fragment offset");
      }

      if (l.getNotIpProtocols() != null && !l.getNotIpProtocols().isEmpty()) {
        throw new BatfishException("detected NOT ip protocols");
      }

      if (local != null) {
        BDD ret;
        if (l.getAction() == LineAction.ACCEPT) {
          ret = _factory.one();
        } else {
          ret = _factory.zero();
        }

        if (l.getNegate()) {
          local = local.not();
        }

        acc = local.ite(ret, acc);
      }
    }

    return acc;
  }

}
