package org.tron.core.net.messagehandler;

import static org.tron.core.config.Parameter.ChainConstant.BLOCK_PRODUCED_INTERVAL;
import static org.tron.core.config.Parameter.ChainConstant.BLOCK_SIZE;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.BlockCapsule.BlockId;
import org.tron.core.exception.P2pException;
import org.tron.core.exception.P2pException.TypeEnum;
import org.tron.core.net.TronNetClient;
import org.tron.core.net.TronProxy;
import org.tron.core.net.message.BlockMessage;
import org.tron.core.net.message.TronMessage;
import org.tron.core.net.peer.Item;
import org.tron.core.net.peer.PeerAdv;
import org.tron.core.net.peer.PeerConnection;
import org.tron.core.net.peer.PeerSync;
import org.tron.core.services.WitnessProductBlockService;
import org.tron.protos.Protocol.Inventory.InventoryType;

@Slf4j
@Component
public class BlockMsgHandler implements TronMsgHandler {

  @Autowired
  private TronProxy tronProxy;

  @Autowired
  private TronNetClient tronManager;

  @Autowired
  private PeerAdv peerAdv;

  @Autowired
  private PeerSync peerSync;

  @Autowired
  private WitnessProductBlockService witnessProductBlockService;

  @Override
  public void processMessage (PeerConnection peer, TronMessage msg) throws Exception {

    BlockMessage blockMessage = (BlockMessage) msg;

    check(peer, blockMessage);

    BlockId blockId = blockMessage.getBlockId();
    Item item = new Item(blockId, InventoryType.BLOCK);
//    boolean syncFlag = false;
    if (peer.getSyncBlockRequested().containsKey(blockId)) {
      peer.getSyncBlockRequested().remove(blockId);
      peerSync.processBlock(peer, blockMessage);
//      syncFlag = true;
    } else {
      peer.getAdvInvRequest().remove(item);
      processBlock(peer, blockMessage.getBlockCapsule());
//      if (!syncFlag) {
//        processBlock(peer, blockMessage.getBlockCapsule());
//      }
    }
  }

  private void check (PeerConnection peer, BlockMessage msg) throws Exception {
    Item item = new Item(msg.getBlockId(), InventoryType.BLOCK);
    if (!peer.getSyncBlockRequested().containsKey(item) && !peer.getAdvInvRequest().containsKey(item)) {
      throw new P2pException(TypeEnum.BAD_MESSAGE, "no request");
    }
    BlockCapsule blockCapsule = msg.getBlockCapsule();
    if (blockCapsule.getInstance().getSerializedSize() > BLOCK_SIZE + 100) {
      throw new P2pException(TypeEnum.BAD_MESSAGE, "block size over limit");
    }
    long gap = blockCapsule.getTimeStamp() - System.currentTimeMillis();
    if (gap >= BLOCK_PRODUCED_INTERVAL) {
      throw new P2pException(TypeEnum.BAD_MESSAGE, "block time error");
    }
  }

  private void processBlock(PeerConnection peer,  BlockCapsule block) throws Exception {
    BlockId blockId = block.getBlockId();
    if (!tronProxy.containBlock(block.getParentBlockId())) {
      logger.warn("Get unlink block {} from {}, head is {}.", blockId.getString(), peer.getInetAddress(), tronProxy.getHeadBlockId().getString());
      peerSync.startSync(peer);
      return;
    }
    tronProxy.processBlock(block);
    witnessProductBlockService.validWitnessProductTwoBlock(block);
    tronProxy.getActivePeer().forEach(p -> {
      if (p.getAdvInvReceive().containsKey(blockId)) {
        p.setBlockBothHave(blockId);
      }
    });
    peerAdv.broadcast(new BlockMessage(block));
  }

}