/**
 * Passenger Dashboard Logic
 * - View available trains
 * - Book tickets
 * - View my bookings
 * - Cancel tickets
 */

let currentUser = null;
let trainList = [];

document.addEventListener('DOMContentLoaded', async () => {
  initLogoutButton();

  // Verify authentication
  currentUser = await checkAuth(['PASSENGER', 'STAFF', 'ADMIN']);
  if (!currentUser) return;

  // Set welcome message & pre-fill name in booking form
  document.querySelector('#welcomeName').textContent = currentUser.fullName || currentUser.username;
  document.querySelector('#bookingPassengerName').value = currentUser.fullName || currentUser.username;

  // Initial load
  await loadData();

  // Refresh button
  document.querySelector('#refreshBtn').addEventListener('click', loadData);

  // Train select change
  document.querySelector('#bookingTrainSelect').addEventListener('change', updateSelectedTrainCard);

  // Booking form submission
  document.querySelector('#passengerBookingForm').addEventListener('submit', handleBookTicket);
});

async function loadData() {
  await Promise.all([loadTrains(), loadMyBookings()]);
}

async function loadTrains() {
  const trainRows = document.querySelector('#trainRows');
  const trainSelect = document.querySelector('#bookingTrainSelect');
  try {
    const res = await fetch('/api/trains');
    if (!res.ok) throw new Error('Could not load trains');
    trainList = await res.json();

    document.querySelector('#statTrainCount').textContent = trainList.length;
    document.querySelector('#trainCountLabel').textContent = `${trainList.length} services`;

    // Render table
    if (trainList.length === 0) {
      trainRows.innerHTML = '<tr><td colspan="5" class="empty-box">No trains currently scheduled.</td></tr>';
      return;
    }

    trainRows.innerHTML = trainList.map(t => {
      const remaining = Math.max(0, t.totalSeats - t.booked);
      const isWaitlist = remaining === 0;
      const statusBadge = isWaitlist
        ? '<span class="badge badge-warning">Waitlist</span>'
        : remaining <= 2
        ? `<span class="badge badge-warning">${remaining} left</span>`
        : `<span class="badge badge-success">${remaining} free</span>`;

      return `
        <tr>
          <td>
            <strong>${escapeHtml(t.name)}</strong>
            <small style="display:block; color:var(--text-dim); font-family:var(--font-mono);">${escapeHtml(t.id)} · ${t.length}m</small>
          </td>
          <td>${formatTime(t.arrival)}</td>
          <td>${formatTime(t.departure)}</td>
          <td>${statusBadge}</td>
          <td>
            <button class="btn-secondary btn-sm" onclick="selectTrainForBooking('${escapeHtml(t.id)}')">
              Book
            </button>
          </td>
        </tr>
      `;
    }).join('');

    // Populate dropdown
    const currentVal = trainSelect.value;
    trainSelect.innerHTML = '<option value="">Choose a train…</option>' + trainList.map(t => {
      const remaining = Math.max(0, t.totalSeats - t.booked);
      const label = `${t.id} - ${t.name} (${remaining} seats free)`;
      return `<option value="${escapeHtml(t.id)}">${escapeHtml(label)}</option>`;
    }).join('');
    if (currentVal && trainList.some(t => t.id === currentVal)) {
      trainSelect.value = currentVal;
    }
    updateSelectedTrainCard();
  } catch (err) {
    trainRows.innerHTML = `<tr><td colspan="5" class="empty-box" style="color:var(--danger)">Error: ${escapeHtml(err.message)}</td></tr>`;
  }
}

function selectTrainForBooking(trainId) {
  const trainSelect = document.querySelector('#bookingTrainSelect');
  trainSelect.value = trainId;
  updateSelectedTrainCard();
  document.querySelector('#bookingSection').scrollIntoView({ behavior: 'smooth', block: 'center' });
}

function updateSelectedTrainCard() {
  const trainId = document.querySelector('#bookingTrainSelect').value;
  const card = document.querySelector('#selectedTrainDetails');
  if (!trainId) {
    card.style.display = 'none';
    return;
  }
  const train = trainList.find(t => t.id === trainId);
  if (!train) {
    card.style.display = 'none';
    return;
  }
  const remaining = Math.max(0, train.totalSeats - train.booked);
  card.style.display = 'block';
  card.innerHTML = `
    <div style="display: flex; justify-content: space-between; align-items: center;">
      <div>
        <strong style="color: var(--primary);">${escapeHtml(train.name)}</strong> (${escapeHtml(train.id)})<br>
        <span style="color: var(--text-dim);">Arrival: ${formatTime(train.arrival)} | Departure: ${formatTime(train.departure)}</span>
      </div>
      <div>
        ${remaining > 0
          ? `<span class="badge badge-success">${remaining} Seats Confirmed</span>`
          : '<span class="badge badge-warning">No Seats Left (Waitlist)</span>'}
      </div>
    </div>
  `;
}

async function loadMyBookings() {
  const bookingRows = document.querySelector('#myBookingRows');
  const badge = document.querySelector('#myBookingBadge');
  try {
    const res = await fetch('/api/my-bookings');
    if (!res.ok) throw new Error('Could not load bookings');
    const bookings = await res.json();

    const confirmed = bookings.filter(b => b.status === 'CONFIRMED').length;
    const waiting = bookings.filter(b => b.status === 'WAITING').length;

    document.querySelector('#statMyConfirmed').textContent = confirmed;
    document.querySelector('#statMyWaiting').textContent = waiting;
    badge.textContent = `${bookings.length} ticket${bookings.length === 1 ? '' : 's'}`;

    if (bookings.length === 0) {
      bookingRows.innerHTML = '<tr><td colspan="6" class="empty-box">You have no active reservations. Select a train above to book!</td></tr>';
      return;
    }

    bookingRows.innerHTML = bookings.map(b => {
      const isConfirmed = b.status === 'CONFIRMED';
      const statusBadge = isConfirmed
        ? '<span class="badge badge-success">Confirmed</span>'
        : '<span class="badge badge-warning">Waiting List</span>';

      const schedule = (b.arrival !== undefined && b.departure !== undefined)
        ? `${formatTime(b.arrival)} – ${formatTime(b.departure)}`
        : 'Scheduled';

      return `
        <tr>
          <td><strong style="font-family: var(--font-mono);">#${b.id}</strong></td>
          <td>${escapeHtml(b.passenger)}</td>
          <td>
            <strong>${escapeHtml(b.trainName)}</strong>
            <small style="display:block; color:var(--text-dim); font-family:var(--font-mono);">${escapeHtml(b.trainId)}</small>
          </td>
          <td>${schedule}</td>
          <td>${statusBadge}</td>
          <td>
            <button class="btn-danger btn-sm" onclick="cancelMyTicket(${b.id})">
              Cancel Ticket
            </button>
          </td>
        </tr>
      `;
    }).join('');
  } catch (err) {
    bookingRows.innerHTML = `<tr><td colspan="7" class="empty-box" style="color:var(--danger)">Error: ${escapeHtml(err.message)}</td></tr>`;
  }
}

async function handleBookTicket(e) {
  e.preventDefault();
  const alert = document.querySelector('#bookingAlert');
  const submitBtn = document.querySelector('#bookSubmitBtn');
  alert.className = 'alert';
  submitBtn.disabled = true;
  submitBtn.textContent = 'Processing booking…';

  try {
    const formData = new FormData(e.target);
    const res = await fetch('/api/bookings', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
      body: new URLSearchParams(formData)
    });
    const result = await res.json();

    if (!res.ok) {
      alert.textContent = result.error || 'Failed to complete booking';
      alert.className = 'alert alert-error show';
      submitBtn.disabled = false;
      submitBtn.textContent = 'Confirm Reservation →';
      return;
    }

    const isConfirmed = result.status === 'CONFIRMED';
    const message = isConfirmed
      ? `Booking Confirmed! Reservation #${result.id} is confirmed.`
      : `Train is full! You have been placed on the FIFO Waiting List (Ticket #${result.id}).`;

    alert.textContent = message;
    alert.className = isConfirmed ? 'alert alert-success show' : 'alert alert-error show';
    showToast(isConfirmed ? 'Ticket Confirmed!' : 'Added to Waiting List');

    // Reset select and reload data
    document.querySelector('#bookingTrainSelect').value = '';
    updateSelectedTrainCard();
    await loadData();
  } catch (err) {
    alert.textContent = 'Connection error: ' + err.message;
    alert.className = 'alert alert-error show';
  } finally {
    submitBtn.disabled = false;
    submitBtn.textContent = 'Confirm Reservation →';
  }
}

async function cancelMyTicket(reservationId) {
  if (!confirm(`Are you sure you want to cancel Ticket #${reservationId}?`)) {
    return;
  }

  try {
    const res = await fetch(`/api/bookings/${reservationId}`, {
      method: 'DELETE'
    });
    const result = await res.json();

    if (!res.ok) {
      showToast(result.error || 'Could not cancel ticket', true);
      return;
    }

    showToast('Ticket cancelled successfully. Waiting passengers are promoted if a seat opened.');
    await loadData();
  } catch (err) {
    showToast('Error cancelling ticket: ' + err.message, true);
  }
}
