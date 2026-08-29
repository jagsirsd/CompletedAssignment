package com.example.demo.readmodel;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ItemReadModelRepository extends JpaRepository<ItemReadModel, Long> {
}
