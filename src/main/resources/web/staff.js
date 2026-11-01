/**
 * Staff Dashboard Logic
 * - View train schedules
 * - View passenger bookings
 * - Manage arrivals and departures
 * - View platform allocation
 */

let staffUser = null;
let trainList = [];
let platformState = null;

document.addEventListener('DOMContentLoaded', async () => {
  initLogoutButton();

  // Verify staff or admin authorization
  staffUser = await checkAuth(['STAFF', 'ADMIN']);
  if (!staffUser) return;

  await loadAllStationData();

  // Refresh button
  document.querySelector('#refreshBtn').addEventListener('click', loadAllStationData);

  // Advance time form (if present)
  const advanceTimeForm = document.querySelector('#advanceTimeForm');
  if (advanceTimeForm) {
    advanceTimeForm.addEventListener('submit', handleAdvanceTime);
  }

  // Apply delay
  document.querySelector('#applyDelayBtn').addEventListener('click', handleApplyDelay);

  // Apply priority
  document.querySelector('#applyPriorityBtn').addEventListener('click', handleApplyPriority);

  // Queue platform request
  document.querySelector('#requestPlatformForm').addEventListener('submit', handleQueueRequest);

  // Allocate next platform
  document.querySelector('#allocatePlatformBtn').addEventListener('click', handleAllocatePlatform);
});

async function loadAllStationData() {
  await Promise.all([loadTrains(), loadPlatformData(), loadAllBookings()]);
}

async function loadTrains() {
  try {
    const res = await fetch('/api/trains');
    if (!res.ok) throw new Error('Could not load trains');
    trainList = await res.json();

    // Populate selects
    const controlSelect = document.querySelector('#controlTrainSelect');
    const reqSelect = document.querySelector('#reqTrainSelect');

    const options = '<option value="">Choose a train…</option>' + trainList.map(t =>
      `<option value="${escapeHtml(t.id)}">${escapeHtml(t.id)} - ${escapeHtml(t.name)} (${t.length}m)</option>`
    ).join('');

    const savedControl = controlSelect.value;
    const savedReq = reqSelect.value;

    controlSelect.innerHTML = options;
    reqSelect.innerHTML = options;

    if (savedControl && trainList.some(t => t.id === savedControl)) controlSelect.value = savedControl;
    if (savedReq && trainList.some(t => t.id === savedReq)) reqSelect.value = savedReq;
  } catch (err) {
    console.error('Error loading trains:', err);
  }
}

async function loadPlatformData() {
  try {
    const res = await fetch('/api/platforms');
    if (!res.ok) throw new Error('Could not load platforms');
    platformState = await res.json();

    const formattedTime = formatTime(platformState.currentTime);
    const headerTime = document.querySelector('#headerStationTime');
    if (headerTime) headerTime.textContent = formattedTime;
    const statClock = document.querySelector('#statClock');
    if (statClock) statClock.textContent = formattedTime;

    document.querySelector('#statQueueCount').textContent = platformState.queue.length;
    document.querySelector('#statTimetableCount').textContent = platformState.timetable.length;
    document.querySelector('#queueBadge').textContent = `${platformState.queue.length} queued`;

    renderPlatformCards(platformState);
    renderQueueList(platformState.queue);
    renderTimetable(platformState);
  } catch (err) {
    console.error('Error loading platform state:', err);
  }
}

function renderPlatformCards(state) {
  const container = document.querySelector('#platformCards');
  container.innerHTML = state.platforms.map(p => {
    const isOccupied = p.occupied;
    const statusText = isOccupied ? `Occupied by ${escapeHtml(p.currentTrainId)}` : 'Physically Clear';
    const statusColor = isOccupied ? 'var(--warning)' : 'var(--success)';
    const assigned = state.timetable.filter(item => item.platformId === p.id);

    return `
      <article class="stat-card" style="border-top: 3px solid ${statusColor};">
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span style="font-family: var(--font-mono); font-size: 1.25rem; font-weight: 700; color: var(--text-main);">${escapeHtml(p.id)}</span>
          <span class="badge ${isOccupied ? 'badge-warning' : 'badge-success'}">${p.length}m</span>
        </div>
        <div style="font-size: 0.9rem; font-weight: 600; color: ${statusColor}; margin: 0.5rem 0;">${statusText}</div>
        <div class="stat-subtitle">${assigned.length} reservation(s) scheduled</div>
      </article>
    `;
  }).join('');
}

function renderQueueList(queue) {
  const container = document.querySelector('#queueListContainer');
  if (queue.length === 0) {
    container.innerHTML = '<div class="empty-box" style="padding: 1.5rem 0;">No platform requests in queue.</div>';
    return;
  }

  container.innerHTML = queue.map(item => {
    const typeBadge = item.requestType === 'EMERGENCY'
      ? '<span class="badge badge-danger">EMERGENCY</span>'
      : item.requestType === 'CONNECTION'
      ? '<span class="badge badge-warning">CONNECTION</span>'
      : '<span class="badge badge-info">SCHEDULED</span>';

    return `
      <div style="display: flex; justify-content: space-between; align-items: center; padding: 0.6rem 0; border-bottom: 1px solid var(--border-color); font-size: 0.85rem;">
        <div>
          <strong>${escapeHtml(item.trainName)}</strong> <small style="color:var(--text-dim); font-family:var(--font-mono);">(${escapeHtml(item.trainId)})</small><br>
          <span style="color: var(--text-dim); font-size: 0.75rem;">Priority ${item.priority} · Order #${item.arrivalOrder}</span>
        </div>
        <div>${typeBadge}</div>
      </div>
    `;
  }).join('');
}

function renderTimetable(state) {
  const tbody = document.querySelector('#timetableRows');
  document.querySelector('#timetableInfo').textContent = `${state.timetable.length} reserved intervals · ${state.clearanceBuffer}m buffer`;

  if (state.timetable.length === 0) {
    tbody.innerHTML = '<tr><td colspan="7" class="empty-box">No platform reservations. Queue train requests and allocate next platform.</td></tr>';
    return;
  }

  const occupiedPlatforms = new Map(state.platforms.map(p => [p.id, p]));

  tbody.innerHTML = state.timetable.map(slot => {
    const platform = occupiedPlatforms.get(slot.platformId);
    const statusText = slot.status === 'DEPARTED_CLEARANCE'
      ? '<span class="badge badge-warning">Departed (Clearing)</span>'
      : slot.status === 'ARRIVED'
      ? '<span class="badge badge-info">Arrived</span>'
      : '<span class="badge badge-success">Reserved</span>';

    // Arrival button enabled only if NOT already arrived AND platform not occupied by someone else
    const arrivalDisabled = slot.arrivalConfirmed || (platform?.occupied && platform.currentTrainId !== slot.trainId);
    // Departure button enabled only if arrived AND NOT already departed
    const departureDisabled = !slot.arrivalConfirmed || slot.departureConfirmed;

    return `
      <tr>
        <td>
          <strong>${escapeHtml(slot.trainName)}</strong>
          <small style="display:block; color:var(--text-dim); font-family:var(--font-mono);">${escapeHtml(slot.trainId)} · ${slot.trainLength}m · P${slot.priority}</small>
        </td>
        <td>${formatTime(slot.start)}</td>
        <td>${formatTime(slot.end - state.clearanceBuffer)}</td>
        <td><strong style="color:var(--primary); font-family:var(--font-mono);">${escapeHtml(slot.platformId)}</strong></td>
        <td>${formatTime(slot.end)}</td>
        <td>${statusText}</td>
        <td>
          <div style="display: flex; gap: 0.35rem;">
            <button class="btn-secondary btn-sm" onclick="confirmArrival('${escapeHtml(slot.trainId)}')" ${arrivalDisabled ? 'disabled' : ''}>
              Arrived
            </button>
            <button class="btn-secondary btn-sm" onclick="confirmDeparture('${escapeHtml(slot.trainId)}')" ${departureDisabled ? 'disabled' : ''}>
              Departed
            </button>
          </div>
        </td>
      </tr>
    `;
  }).join('');
}

async function loadAllBookings() {
  const tbody = document.querySelector('#allBookingRows');
  const badge = document.querySelector('#allBookingsBadge');
  try {
    const res = await fetch('/api/bookings');
    if (!res.ok) throw new Error('Could not load bookings');
    const bookings = await res.json();

    document.querySelector('#statBookingsCount').textContent = bookings.length;
    badge.textContent = `${bookings.length} active`;

    if (bookings.length === 0) {
      tbody.innerHTML = '<tr><td colspan="5" class="empty-box">No active passenger bookings found.</td></tr>';
      return;
    }

    tbody.innerHTML = bookings.map(b => {
      const isConfirmed = b.status === 'CONFIRMED';
      const statusBadge = isConfirmed
        ? '<span class="badge badge-success">Confirmed</span>'
        : '<span class="badge badge-warning">Waiting List</span>';

      return `
        <tr>
          <td><strong style="font-family: var(--font-mono);">#${b.id}</strong></td>
          <td>${escapeHtml(b.passenger)}</td>
          <td>${escapeHtml(b.trainName)} <small style="color:var(--text-dim); font-family:var(--font-mono);">(${escapeHtml(b.trainId)})</small></td>
          <td>${statusBadge}</td>
          <td>
            <button class="btn-danger btn-sm" onclick="cancelAnyBooking(${b.id})">
              Cancel
            </button>
          </td>
        </tr>
      `;
    }).join('');
  } catch (err) {
    tbody.innerHTML = `<tr><td colspan="5" class="empty-box" style="color:var(--danger)">Error: ${escapeHtml(err.message)}</td></tr>`;
  }
}

// Operational Actions

async function handleAdvanceTime(e) {
  e.preventDefault();
  const minutes = document.querySelector('#advanceMinutesInput').value;
  try {
    const res = await fetch('/api/station/time', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
      body: new URLSearchParams({ minutes })
    });
    const result = await res.json();
    if (!res.ok) throw new Error(result.error || result.message || 'Failed to advance time');
    showToast(result.message || `Station time advanced by ${minutes} minutes.`);
    await loadPlatformData();
  } catch (err) {
    showToast(err.message, true);
  }
}

async function handleApplyDelay() {
  const trainId = document.querySelector('#controlTrainSelect').value;
  const minutes = document.querySelector('#delayInput').value;
  if (!trainId) return showToast('Please select a train first', true);

  try {
    const res = await fetch(`/api/trains/${encodeURIComponent(trainId)}/delay`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
      body: new URLSearchParams({ minutes })
    });
    const result = await res.json();
    if (!res.ok) throw new Error(result.error || result.message || 'Failed to apply delay');
    showToast(result.message || `Delay applied to ${trainId}.`);
    await loadAllStationData();
  } catch (err) {
    showToast(err.message, true);
  }
}

async function handleApplyPriority() {
  const trainId = document.querySelector('#controlTrainSelect').value;
  const priority = document.querySelector('#prioritySelect').value;
  if (!trainId) return showToast('Please select a train first', true);

  try {
    const res = await fetch(`/api/trains/${encodeURIComponent(trainId)}/priority`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
      body: new URLSearchParams({ priority })
    });
    const result = await res.json();
    if (!res.ok) throw new Error(result.error || result.message || 'Failed to update priority');
    showToast(result.message || `Priority updated for ${trainId}.`);
    await loadAllStationData();
  } catch (err) {
    showToast(err.message, true);
  }
}

async function handleQueueRequest(e) {
  e.preventDefault();
  const form = e.target;
  try {
    const res = await fetch('/api/platform-requests', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
      body: new URLSearchParams(new FormData(form))
    });
    const result = await res.json();
    if (!res.ok) throw new Error(result.error || result.message || 'Failed to queue request');
    showToast(result.message || 'Platform request queued.');
    form.reset();
    await loadPlatformData();
  } catch (err) {
    showToast(err.message, true);
  }
}

async function handleAllocatePlatform() {
  try {
    const res = await fetch('/api/platform-allocations', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' }
    });
    const result = await res.json();
    if (!res.ok) throw new Error(result.error || result.message || 'Failed to allocate platform');
    showToast(result.message || 'Platform allocated.');
    await loadPlatformData();
  } catch (err) {
    showToast(err.message, true);
  }
}

async function confirmArrival(trainId) {
  try {
    const res = await fetch(`/api/platform-occupations/${encodeURIComponent(trainId)}/arrival`, {
      method: 'POST'
    });
    const result = await res.json();
    if (!res.ok) throw new Error(result.error || result.message || 'Failed to confirm arrival');
    showToast(result.message || `Train ${trainId} arrival confirmed.`);
    await loadPlatformData();
  } catch (err) {
    showToast(err.message, true);
  }
}

async function confirmDeparture(trainId) {
  try {
    const res = await fetch(`/api/platform-occupations/${encodeURIComponent(trainId)}/departure`, {
      method: 'POST'
    });
    const result = await res.json();
    if (!res.ok) throw new Error(result.error || result.message || 'Failed to confirm departure');
    showToast(result.message || `Train ${trainId} departure confirmed. Clearing buffer active.`);
    await loadPlatformData();
  } catch (err) {
    showToast(err.message, true);
  }
}

async function cancelAnyBooking(reservationId) {
  if (!confirm(`Are you sure you want to cancel Ticket #${reservationId}?`)) return;

  try {
    const res = await fetch(`/api/bookings/${reservationId}`, { method: 'DELETE' });
    const result = await res.json();
    if (!res.ok) throw new Error(result.error || result.message || 'Could not cancel booking');
    showToast('Booking cancelled. Waiting list checked for promotion.');
    await loadAllStationData();
  } catch (err) {
    showToast(err.message, true);
  }
}
