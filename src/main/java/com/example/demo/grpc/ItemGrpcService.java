package com.example.demo.grpc;

import com.example.demo.dto.ItemDto;
import com.example.demo.dto.PagedResult;
import com.example.demo.entity.Item;
import com.example.demo.grpc.v1.CreateItemRequest;
import com.example.demo.grpc.v1.DeleteItemRequest;
import com.example.demo.grpc.v1.GetItemRequest;
import com.example.demo.grpc.v1.ItemMessage;
import com.example.demo.grpc.v1.ItemServiceGrpc;
import com.example.demo.grpc.v1.ListItemsRequest;
import com.example.demo.grpc.v1.ListItemsResponse;
import com.example.demo.readmodel.ItemReadStore;
import com.example.demo.service.ItemCommandService;
import com.google.protobuf.Empty;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
public class ItemGrpcService extends ItemServiceGrpc.ItemServiceImplBase {

    private final ItemReadStore itemReadStore;
    private final ItemCommandService itemCommandService;

    public ItemGrpcService(ItemReadStore itemReadStore, ItemCommandService itemCommandService) {
        this.itemReadStore = itemReadStore;
        this.itemCommandService = itemCommandService;
    }

    @Override
    public void createItem(CreateItemRequest request, StreamObserver<ItemMessage> responseObserver) {
        Item item = new Item(request.getName(), request.getDescription());
        Item created = itemCommandService.create(item);
        responseObserver.onNext(ItemGrpcMapper.toMessage(created));
        responseObserver.onCompleted();
    }

    @Override
    public void getItem(GetItemRequest request, StreamObserver<ItemMessage> responseObserver) {
        itemReadStore.findById(request.getId())
                .map(ItemGrpcMapper::toMessage)
                .ifPresentOrElse(message -> {
                    responseObserver.onNext(message);
                    responseObserver.onCompleted();
                }, () -> responseObserver.onError(notFound(request.getId())));
    }

    @Override
    public void listItems(ListItemsRequest request, StreamObserver<ListItemsResponse> responseObserver) {
        PagedResult<ItemDto> paged = itemReadStore.findAll(ItemGrpcMapper.toPageable(request));
        responseObserver.onNext(ItemGrpcMapper.toListResponse(paged));
        responseObserver.onCompleted();
    }

    @Override
    public void deleteItem(DeleteItemRequest request, StreamObserver<Empty> responseObserver) {
        if (!itemCommandService.delete(request.getId())) {
            responseObserver.onError(notFound(request.getId()));
            return;
        }
        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();
    }

    private static io.grpc.StatusRuntimeException notFound(long id) {
        return Status.NOT_FOUND.withDescription("Item " + id + " not found").asRuntimeException();
    }
}
