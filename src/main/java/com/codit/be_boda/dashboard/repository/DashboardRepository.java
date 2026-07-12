package com.codit.be_boda.dashboard.repository;

import com.codit.be_boda.dashboard.domain.Dashboard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DashboardRepository extends JpaRepository<Dashboard, Long> {

    List<Dashboard> findByUser_Id(Long userId);
}