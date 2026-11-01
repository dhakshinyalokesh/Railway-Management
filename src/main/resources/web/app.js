const trainRows = document.querySelector('#trainRows');
const bookingRows = document.querySelector('#bookingRows');
const trainSelect = document.querySelector('#trainSelect');
const bookingForm = document.querySelector('#bookingForm');
const platformRequestForm = document.querySelector('#platformRequestForm');
let trains = [];

document.querySelector('#todayLabel').textContent = new Intl.DateTimeFormat(undefined, {
  weekday: 'short', month: 'short', day: 'numeric'
}).format(new Date());

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, char => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  })[char]);
}

function formatTime(minutes) {
  const hour = Math.floor(minutes / 60) % 24;
  const minute = minutes % 60;
  return `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;
}

async function loadDashboard() {
  try {
    const [trainResponse, bookingResponse, platformResponse] = await Promise.all([
      fetch('/api/trains'), fetch('/api/bookings'), fetch('/api/platforms')
    ]);
    if (!trainResponse.ok || !bookingResponse.ok || !platformResponse.ok) throw new Error('Could not load station data');
    trains = await trainResponse.json();
    const bookings = await bookingResponse.json();
    const platformPlan = await platformResponse.json();
    renderTrains();
    renderServices();
    renderBookings(bookings);
    renderPlatforms(platformPlan);
    const booked = trains.reduce((sum, train) => sum + train.booked, 0);
    const capacity = trains.reduce((sum, train) => sum + train.totalSeats, 0);
    document.querySelector('#trainCount').textContent = trains.length;
    document.querySelector('#confirmedCount').textContent = booked;
    document.querySelector('#availableCount').textContent = capacity - booked;
    document.querySelector('#waitingCount').textContent = bookings.filter(item => item.status === 'WAITING').length;
    document.querySelector('#trainFoot').textContent = `${trains.length} services in today's schedule`;
    updateTrainSelect();
  } catch (error) {
    trainRows.innerHTML = `<tr><td colspan="6" class="loading">${escapeHtml(error.message)}. Is the Java server running?</td></tr>`;
    document.querySelector('#popularRoutes').innerHTML = '<article class="service-placeholder">Connect to the station desk to load services.</article>';
    bookingRows.innerHTML = '<div class="empty-state">Connect to the server to view reservations.</div>';
    document.querySelector('#platformCards').innerHTML = '<div class="empty-state">Connect to the station desk to load platform operations.</div>';
    document.querySelector('#platformQueue').innerHTML = '<div class="empty-state">Platform requests are unavailable.</div>';
    document.querySelector('#platformRows').innerHTML = '<tr><td colspan="6" class="loading">Platform schedule is unavailable.</td></tr>';
  }
}

function renderPlatforms(state) {
  document.querySelector('#stationTime').textContent = formatTime(state.currentTime);
  const cards = document.querySelector('#platformCards');
  cards.innerHTML = state.platforms.map(platform => {
    const assigned = state.timetable.filter(item => item.platformId === platform.id);
    const occupying = platform.occupied ? `Occupied by ${escapeHtml(platform.currentTrainId)}` : 'Physically clear';
    return `<article class="platform-card">
      <div class="platform-card-head"><span class="platform-label">${escapeHtml(platform.id)}</span><span class="platform-length">${platform.length} m · ${occupying}</span></div>
      <div class="platform-track"><i></i></div>
      <div class="platform-card-foot"><strong>${assigned.length} reserved</strong><span>${assigned.length ? assigned.map(item => `${escapeHtml(item.trainId)} ${formatTime(item.start)}–${formatTime(item.end)}`).join(' · ') : 'No timetable reservations'}</span></div>
    </article>`;
  }).join('');

  document.querySelector('#platformQueueBadge').textContent = `${state.queue.length} queued`;
  document.querySelector('#platformQueue').innerHTML = state.queue.length ? state.queue.map(item => `
    <div class="queue-row"><span class="queue-train">${escapeHtml(item.trainName)}<small>${escapeHtml(item.trainId)} · request #${item.requestId}</small></span>
    <span class="request-pill ${item.requestType.toLowerCase()}">${escapeHtml(item.requestType)}</span>
    <span>Train priority ${item.priority}</span><span>Arrival ${formatTime(item.arrival)} · order ${item.arrivalOrder}</span></div>`).join('')
    : '<div class="empty-state">No pending platform requests. Add a train above to start the allocator.</div>';

  const occupiedPlatform = new Map(state.platforms.map(platform => [platform.id, platform]));
  document.querySelector('#platformRows').innerHTML = state.timetable.length ? state.timetable.map(item => {
    const platform = occupiedPlatform.get(item.platformId);
    const statusText = item.status === 'DEPARTED_CLEARANCE' ? 'Clearance in progress'
      : item.status === 'ARRIVED' ? 'Train arrived' : 'Reserved';
    const arrivalDisabled = item.arrivalConfirmed || (platform?.occupied && platform.currentTrainId !== item.trainId);
    const departureDisabled = !item.arrivalConfirmed || item.departureConfirmed;
    return `
    <tr>
      <td><div class="train-cell"><span class="train-glyph">↗</span><span>${escapeHtml(item.trainName)}<small class="train-sub">${escapeHtml(item.trainId)} · ${item.trainLength} m · priority ${item.priority}</small></span></div></td>
      <td>${formatTime(item.start)}</td><td>${formatTime(item.end - state.clearanceBuffer)}</td>
      <td>${escapeHtml(item.platformId)}</td><td>${formatTime(item.end)}</td>
      <td><span class="table-status ${item.status === 'RESERVED' ? '' : 'limited'}">${statusText}</span>
        <div class="platform-row-actions"><button class="platform-action-button" data-arrive="${escapeHtml(item.trainId)}" ${arrivalDisabled ? 'disabled' : ''}>Arrived</button>
        <button class="platform-action-button" data-depart="${escapeHtml(item.trainId)}" ${departureDisabled ? 'disabled' : ''}>Departed</button></div></td>
    </tr>`;
  }).join('') : '<tr><td colspan="6" class="empty-state">No platform reservations yet. Queue requests and allocate a platform.</td></tr>';

  document.querySelector('#platformFoot').textContent = `${state.timetable.length} reservations · ${state.clearanceBuffer} minute clearance buffer · ${formatTime(state.currentTime)} station time`;
  document.querySelector('#platformRows').querySelectorAll('[data-arrive]').forEach(button => button.addEventListener('click', () =>
    platformAction(`/api/platform-occupations/${encodeURIComponent(button.dataset.arrive)}/arrival`, 'Arrival confirmed.')));
  document.querySelector('#platformRows').querySelectorAll('[data-depart]').forEach(button => button.addEventListener('click', () =>
    platformAction(`/api/platform-occupations/${encodeURIComponent(button.dataset.depart)}/departure`, 'Departure confirmed. Platform remains reserved until clearance.')));
}

function renderTrains() {
  trainRows.innerHTML = trains.map(train => {
    const remaining = Math.max(0, train.totalSeats - train.booked);
    const percent = Math.min(100, train.booked / train.totalSeats * 100);
    const statusClass = remaining <= 2 ? 'limited' : '';
    const statusText = remaining === 0 ? 'Waitlist' : remaining <= 2 ? 'Filling fast' : 'On time';
    return `<tr>
      <td><div class="train-cell"><span class="train-glyph">↗</span><span>${escapeHtml(train.name)}<small class="train-sub">${escapeHtml(train.id)} · ${train.length} m</small></span></div></td>
      <td>${formatTime(train.arrival)}</td><td>${formatTime(train.departure)}</td>
      <td class="seat-cell"><span class="seat-number">${remaining} of ${train.totalSeats} free</span><span class="seat-track"><i style="width:${percent}%"></i></span></td>
      <td><span class="table-status ${statusClass}">${statusText}</span></td>
      <td><button class="book-row-button" data-train="${escapeHtml(train.id)}">Book</button></td>
    </tr>`;
  }).join('');
  attachTrainActions(trainRows);
}

function renderServices() {
  const serviceGrid = document.querySelector('#popularRoutes');
  serviceGrid.innerHTML = trains.map(train => {
    const remaining = Math.max(0, train.totalSeats - train.booked);
    const status = remaining === 0 ? 'Waitlist only' : remaining <= 2 ? 'Filling fast' : 'Seats available';
    const statusClass = remaining <= 2 ? 'limited' : '';
    return `<article class="service-card">
      <div class="service-card-top"><span class="service-id">${escapeHtml(train.id)} · SERVICE</span><span class="service-badge ${statusClass}">${status}</span></div>
      <h3 class="service-name">${escapeHtml(train.name)}</h3>
      <div class="service-meta"><span>↗ ${formatTime(train.arrival)}</span><span>→ ${formatTime(train.departure)}</span><span>${train.length} m</span></div>
      <div class="service-bottom"><span class="service-seats">${remaining} of ${train.totalSeats} seats free</span><button class="service-book" data-train="${escapeHtml(train.id)}">Choose service</button></div>
    </article>`;
  }).join('');
  attachTrainActions(serviceGrid);
}

function attachTrainActions(container) {
  container.querySelectorAll('[data-train]').forEach(button => button.addEventListener('click', () => {
    trainSelect.value = button.dataset.train;
    updateSelectedTrain();
    document.querySelector('#passengerName').focus();
    document.querySelector('#bookingPanel').scrollIntoView({ behavior: 'smooth', block: 'center' });
  }));
}

function renderBookings(bookings) {
  document.querySelector('#reservationBadge').textContent = `${bookings.length} active`;
  if (!bookings.length) {
    bookingRows.innerHTML = '<div class="empty-state">No reservations yet. Book a seat to see it here.</div>';
    return;
  }
  bookingRows.innerHTML = bookings.map(booking => {
    const initials = booking.passenger.split(/\s+/).slice(0, 2).map(name => name[0]?.toUpperCase() || '').join('');
    return `<div class="reservation-row">
      <div class="passenger-info"><span class="passenger-avatar">${escapeHtml(initials)}</span><span class="passenger-name">${escapeHtml(booking.passenger)}<small class="passenger-caption">Reservation #${booking.id}</small></span></div>
      <span>${escapeHtml(booking.trainName)}<small class="passenger-caption">${escapeHtml(booking.trainId)}</small></span>
      <span class="reservation-time">${booking.status === 'WAITING' ? 'In queue' : 'Seat confirmed'}</span>
      <span class="booking-status ${booking.status === 'WAITING' ? 'waiting' : ''}">${booking.status === 'WAITING' ? 'Waiting' : 'Confirmed'}</span>
      <button class="cancel-button" data-cancel="${booking.id}">Cancel</button>
    </div>`;
  }).join('');
  bookingRows.querySelectorAll('[data-cancel]').forEach(button => button.addEventListener('click', () => cancelBooking(button.dataset.cancel)));
}

function updateTrainSelect() {
  const previous = trainSelect.value;
  trainSelect.innerHTML = '<option value="">Select a service</option>' + trains.map(train =>
    `<option value="${escapeHtml(train.id)}">${escapeHtml(train.id)} · ${escapeHtml(train.name)}</option>`
  ).join('');
  if (trains.some(train => train.id === previous)) trainSelect.value = previous;
  for (const id of ['platformTrainSelect', 'controlTrainSelect']) {
    const select = document.querySelector(`#${id}`);
    const selected = select.value;
    select.innerHTML = '<option value="">Choose a train</option>' + trains.map(train =>
      `<option value="${escapeHtml(train.id)}">${escapeHtml(train.id)} · ${escapeHtml(train.name)}</option>`
    ).join('');
    if (trains.some(train => train.id === selected)) select.value = selected;
  }
  updateSelectedTrain();
}

function updateSelectedTrain() {
  const train = trains.find(item => item.id === trainSelect.value);
  const info = document.querySelector('#selectedTrainInfo');
  info.innerHTML = train
    ? `<span class="selected-icon">↗</span><span><strong>${escapeHtml(train.name)}</strong><small>${formatTime(train.arrival)} arrival · ${Math.max(0, train.totalSeats - train.booked)} seats available</small></span>`
    : '<span class="selected-icon">↗</span><span><strong>Select a train</strong><small>Schedule details will appear here</small></span>';
}

async function postStationForm(url, values) {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
    body: new URLSearchParams(values)
  });
  const result = await response.json();
  if (!response.ok) throw new Error(result.message || result.error || 'Station operation failed');
  showToast(result.message || 'Station updated');
  await loadDashboard();
}

async function platformAction(url) {
  try {
    await postStationForm(url, {});
  } catch (error) {
    showToast(error.message);
  }
}

platformRequestForm.addEventListener('submit', async event => {
  event.preventDefault();
  try {
    await postStationForm('/api/platform-requests', new FormData(platformRequestForm));
    platformRequestForm.reset();
    document.querySelector('#platformMessage').textContent = 'Request added. Emergency and connection requests are ordered ahead of scheduled arrivals.';
  } catch (error) {
    showToast(error.message);
  }
});

document.querySelector('#allocatePlatformButton').addEventListener('click', () =>
  platformAction('/api/platform-allocations'));

document.querySelector('#stationTimeForm').addEventListener('submit', async event => {
  event.preventDefault();
  try {
    await postStationForm('/api/station/time', new FormData(event.currentTarget));
  } catch (error) {
    showToast(error.message);
  }
});

document.querySelector('#applyDelayButton').addEventListener('click', async () => {
  const trainId = document.querySelector('#controlTrainSelect').value;
  if (!trainId) return showToast('Choose a train first');
  try {
    await postStationForm(`/api/trains/${encodeURIComponent(trainId)}/delay`, {
      minutes: document.querySelector('#delayMinutes').value
    });
  } catch (error) {
    showToast(error.message);
  }
});

document.querySelector('#applyPriorityButton').addEventListener('click', async () => {
  const trainId = document.querySelector('#controlTrainSelect').value;
  if (!trainId) return showToast('Choose a train first');
  try {
    await postStationForm(`/api/trains/${encodeURIComponent(trainId)}/priority`, {
      priority: document.querySelector('#prioritySelect').value
    });
  } catch (error) {
    showToast(error.message);
  }
});

async function cancelBooking(id) {
  if (!window.confirm(`Cancel reservation #${id}?`)) return;
  const response = await fetch(`/api/bookings/${id}`, { method: 'DELETE' });
  const result = await response.json();
  if (!response.ok) return showToast(result.error || 'Could not cancel this reservation');
  showToast('Reservation cancelled. The next waiting passenger is promoted when possible.');
  await loadDashboard();
}

bookingForm.addEventListener('submit', async event => {
  event.preventDefault();
  const message = document.querySelector('#formMessage');
  message.className = 'form-message';
  message.textContent = 'Saving reservation…';
  const response = await fetch('/api/bookings', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
    body: new URLSearchParams(new FormData(bookingForm))
  });
  const result = await response.json();
  if (!response.ok) {
    message.classList.add('error');
    message.textContent = result.error || 'Could not save reservation.';
    return;
  }
  message.textContent = result.status === 'WAITING'
    ? `Added to the waiting list · Reservation #${result.id}`
    : `Seat confirmed · Reservation #${result.id}`;
  bookingForm.reset();
  updateSelectedTrain();
  showToast(result.status === 'WAITING' ? 'Added to the waiting list' : 'Reservation confirmed');
  await loadDashboard();
});

function showToast(message) {
  const toast = document.querySelector('#toast');
  toast.textContent = message;
  toast.classList.add('show');
  window.setTimeout(() => toast.classList.remove('show'), 3200);
}

trainSelect.addEventListener('change', updateSelectedTrain);
document.querySelector('#refreshButton').addEventListener('click', loadDashboard);
document.querySelector('#newBookingButton').addEventListener('click', () => {
  document.querySelector('#bookingPanel').scrollIntoView({ behavior: 'smooth', block: 'center' });
  document.querySelector('#passengerName').focus({ preventScroll: true });
});
loadDashboard();
