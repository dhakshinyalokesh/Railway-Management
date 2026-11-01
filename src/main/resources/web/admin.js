/**
 * Admin Dashboard Logic
 * - View all trains and bookings
 * - Manage users (create, delete, list)
 * - Manage train information (create new train routes)
 * - View platform allocation
 */

let adminUser = null;

document.addEventListener('DOMContentLoaded', async () => {
  initLogoutButton();
  initPasswordToggles();

  // Verify administrator authorization
  adminUser = await checkAuth(['ADMIN']);
  if (!adminUser) return;

  await loadAllAdminData();

  // Refresh
  document.querySelector('#refreshBtn').addEventListener('click', loadAllAdminData);

  // Forms
  document.querySelector('#createUserForm').addEventListener('submit', handleCreateUser);
  const createTrainForm = document.querySelector('#createTrainForm');
  if (createTrainForm) {
    createTrainForm.addEventListener('submit', handleCreateTrain);
  }
});

async function loadAllAdminData() {
  await Promise.all([loadUsers(), loadTrains(), loadPlatformOverview(), loadAllBookings()]);
}

async function loadUsers() {
  const tbody = document.querySelector('#userRows');
  const badge = document.querySelector('#userCountBadge');
  try {
    const res = await fetch('/api/admin/users');
    if (!res.ok) throw new Error('Could not load users');
    const users = await res.json();

    document.querySelector('#statTotalUsers').textContent = users.length;
    badge.textContent = `${users.length} registered`;

    if (users.length === 0) {
      tbody.innerHTML = '<tr><td colspan="6" class="empty-box">No users in database.</td></tr>';
      return;
    }

    tbody.innerHTML = users.map(u => {
      const isSelf = u.userId === adminUser.userId;
      const roleBadge = u.role === 'ADMIN'
        ? '<span class="role-tag role-admin">ADMIN</span>'
        : u.role === 'STAFF'
        ? '<span class="role-tag role-staff">STAFF</span>'
        : '<span class="role-tag role-passenger">PASSENGER</span>';

      return `
        <tr>
          <td><strong style="font-family: var(--font-mono);">${u.userId}</strong></td>
          <td>${escapeHtml(u.username)}</td>
          <td>${escapeHtml(u.fullName)}</td>
          <td>${roleBadge}</td>
          <td style="color: var(--text-dim); font-size: 0.75rem;">${escapeHtml(u.createdAt ? u.createdAt.substring(0, 10) : '—')}</td>
          <td>
            ${isSelf
              ? '<small style="color: var(--text-dim);">(You)</small>'
              : `<button class="btn-danger btn-sm" onclick="deleteUserAccount(${u.userId}, '${escapeHtml(u.username)}')">Delete</button>`
            }
          </td>
        </tr>
      `;
    }).join('');
  } catch (err) {
    tbody.innerHTML = `<tr><td colspan="6" class="empty-box" style="color:var(--danger)">Error: ${escapeHtml(err.message)}</td></tr>`;
  }
}

async function handleCreateUser(e) {
  e.preventDefault();
  const alert = document.querySelector('#createUserAlert');
  const submitBtn = document.querySelector('#createUserSubmitBtn');
  alert.className = 'alert';
  submitBtn.disabled = true;

  try {
    const formData = new FormData(e.target);
    const res = await fetch('/api/admin/users', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
      body: new URLSearchParams(formData)
    });
    const result = await res.json();

    if (!res.ok) {
      alert.textContent = result.error || 'Failed to create user';
      alert.className = 'alert alert-error show';
      return;
    }

    alert.textContent = result.message || 'User created successfully!';
    alert.className = 'alert alert-success show';
    e.target.reset();
    showToast('New user account created.');
    await loadUsers();
  } catch (err) {
    alert.textContent = 'Error: ' + err.message;
    alert.className = 'alert alert-error show';
  } finally {
    submitBtn.disabled = false;
  }
}

async function deleteUserAccount(userId, username) {
  if (!confirm(`Are you sure you want to permanently delete user "${username}" (ID: ${userId})?`)) {
    return;
  }

  try {
    const res = await fetch(`/api/admin/users/${userId}`, { method: 'DELETE' });
    const result = await res.json();

    if (!res.ok) {
      showToast(result.error || 'Failed to delete user', true);
      return;
    }

    showToast(`User ${username} deleted.`);
    await loadUsers();
  } catch (err) {
    showToast('Error: ' + err.message, true);
  }
}

async function loadTrains() {
  const tbody = document.querySelector('#trainMasterRows');
  const badge = document.querySelector('#trainCountBadge');
  try {
    const res = await fetch('/api/trains');
    if (!res.ok) throw new Error('Could not load trains');
    const trains = await res.json();

    document.querySelector('#statTotalTrains').textContent = trains.length;
    badge.textContent = `${trains.length} services`;

    const bookedTotal = trains.reduce((sum, t) => sum + t.booked, 0);
    document.querySelector('#statConfirmedSeats').textContent = bookedTotal;

    if (trains.length === 0) {
      tbody.innerHTML = '<tr><td colspan="6" class="empty-box">No trains configured.</td></tr>';
      return;
    }

    tbody.innerHTML = trains.map(t => {
      const remaining = Math.max(0, t.totalSeats - t.booked);
      return `
        <tr>
          <td>
            <strong>${escapeHtml(t.name)}</strong>
            <small style="display:block; color:var(--text-dim); font-family:var(--font-mono);">${escapeHtml(t.id)}</small>
          </td>
          <td>${formatTime(t.arrival)}</td>
          <td>${formatTime(t.departure)}</td>
          <td>${t.length} m</td>
          <td><span class="badge badge-info">P${t.priority}</span></td>
          <td>${remaining} / ${t.totalSeats} free</td>
        </tr>
      `;
    }).join('');
  } catch (err) {
    tbody.innerHTML = `<tr><td colspan="6" class="empty-box" style="color:var(--danger)">Error: ${escapeHtml(err.message)}</td></tr>`;
  }
}

async function handleCreateTrain(e) {
  e.preventDefault();
  const alert = document.querySelector('#createTrainAlert');
  const submitBtn = document.querySelector('#createTrainSubmitBtn');
  alert.className = 'alert';
  submitBtn.disabled = true;

  try {
    const formData = new FormData(e.target);
    const res = await fetch('/api/admin/trains', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
      body: new URLSearchParams(formData)
    });
    const result = await res.json();

    if (!res.ok) {
      alert.textContent = result.error || 'Failed to add train';
      alert.className = 'alert alert-error show';
      return;
    }

    alert.textContent = result.message || 'Train service added successfully!';
    alert.className = 'alert alert-success show';
    e.target.reset();
    showToast('Train service added.');
    await loadTrains();
  } catch (err) {
    alert.textContent = 'Error: ' + err.message;
    alert.className = 'alert alert-error show';
  } finally {
    submitBtn.disabled = false;
  }
}

async function loadPlatformOverview() {
  const cards = document.querySelector('#adminPlatformCards');
  const tbody = document.querySelector('#adminTimetableRows');
  try {
    const res = await fetch('/api/platforms');
    if (!res.ok) throw new Error('Could not load platforms');
    const state = await res.json();

    document.querySelector('#adminClockDisplay').textContent = `Station Clock: ${formatTime(state.currentTime)}`;

    cards.innerHTML = state.platforms.map(p => {
      const isOccupied = p.occupied;
      const occupying = isOccupied ? `Occupied (${escapeHtml(p.currentTrainId)})` : 'Physically Clear';
      const assigned = state.timetable.filter(item => item.platformId === p.id);
      return `
        <article class="stat-card" style="border-top: 3px solid ${isOccupied ? 'var(--warning)' : 'var(--success)'};">
          <div style="display:flex; justify-content:space-between;">
            <strong style="font-family: var(--font-mono); font-size:1.15rem;">${escapeHtml(p.id)}</strong>
            <span class="badge ${isOccupied ? 'badge-warning' : 'badge-success'}">${p.length}m</span>
          </div>
          <div style="margin: 0.5rem 0; font-size:0.85rem; font-weight:600;">${occupying}</div>
          <div class="stat-subtitle">${assigned.length} reservations</div>
        </article>
      `;
    }).join('');

    if (state.timetable.length === 0) {
      tbody.innerHTML = '<tr><td colspan="5" class="empty-box">No platform reservations.</td></tr>';
      return;
    }

    tbody.innerHTML = state.timetable.map(slot => `
      <tr>
        <td><strong>${escapeHtml(slot.trainName)}</strong> (${escapeHtml(slot.trainId)})</td>
        <td><strong style="color:var(--primary);">${escapeHtml(slot.platformId)}</strong></td>
        <td>${formatTime(slot.start)}</td>
        <td>${formatTime(slot.end)}</td>
        <td><span class="badge badge-info">${escapeHtml(slot.status)}</span></td>
      </tr>
    `).join('');
  } catch (err) {
    console.error('Error loading platforms:', err);
  }
}

async function loadAllBookings() {
  const tbody = document.querySelector('#adminBookingRows');
  const badge = document.querySelector('#adminBookingsBadge');
  try {
    const res = await fetch('/api/bookings');
    if (!res.ok) throw new Error('Could not load bookings');
    const bookings = await res.json();

    document.querySelector('#statTotalBookings').textContent = bookings.length;
    badge.textContent = `${bookings.length} reservations`;

    const waiting = bookings.filter(b => b.status === 'WAITING').length;
    document.querySelector('#statWaitingSubtitle').textContent = `${waiting} in waiting list`;

    if (bookings.length === 0) {
      tbody.innerHTML = '<tr><td colspan="5" class="empty-box">No active bookings.</td></tr>';
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
          <td>${escapeHtml(b.trainName)} <small style="color:var(--text-dim);">(${escapeHtml(b.trainId)})</small></td>
          <td>${statusBadge}</td>
          <td>
            <button class="btn-danger btn-sm" onclick="adminCancelBooking(${b.id})">
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

async function adminCancelBooking(id) {
  if (!confirm(`Cancel reservation #${id}?`)) return;

  try {
    const res = await fetch(`/api/bookings/${id}`, { method: 'DELETE' });
    const result = await res.json();
    if (!res.ok) throw new Error(result.error || result.message || 'Could not cancel');
    showToast('Booking cancelled.');
    await loadAllAdminData();
  } catch (err) {
    showToast(err.message, true);
  }
}
