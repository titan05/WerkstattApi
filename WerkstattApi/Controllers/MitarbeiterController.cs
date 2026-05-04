using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using WerkstattApi.Models;

namespace WerkstattApi.Controllers
{
    [Route("api/[controller]")]
    [ApiController]
    public class MitarbeiterController : ControllerBase
    {
        private readonly WerkstattApiContext _context;

        public MitarbeiterController(WerkstattApiContext context)
        {
            _context = context;
        }

        // GET: api/Mitarbeiter
        [HttpGet]
        public async Task<ActionResult<IEnumerable<Mitarbeiter>>> GetMitarbeiter()
        {
            return await _context.Mitarbeiter.Include(m => m.Werkstatt).ToListAsync();
        }

        // GET: api/Mitarbeiter/5
        [HttpGet("{id}")]
        public async Task<ActionResult<Mitarbeiter>> GetMitarbeiter(int id)
        {
            var mitarbeiter = await _context.Mitarbeiter.FindAsync(id);

            if (mitarbeiter == null)
            {
                return NotFound();
            }

            return mitarbeiter;
        }

        // PUT: api/Mitarbeiter/5
        // To protect from overposting attacks, see https://go.microsoft.com/fwlink/?linkid=2123754
        [HttpPut("{id}")]
        public async Task<IActionResult> PutMitarbeiter(int id, Mitarbeiter mitarbeiter)
        {
            if (id != mitarbeiter.Id)
            {
                return BadRequest();
            }

            _context.Entry(mitarbeiter).State = EntityState.Modified;

            try
            {
                await _context.SaveChangesAsync();
            }
            catch (DbUpdateConcurrencyException)
            {
                if (!MitarbeiterExists(id))
                {
                    return NotFound();
                }
                else
                {
                    throw;
                }
            }

            return NoContent();
        }

        // POST: api/Mitarbeiter
        // To protect from overposting attacks, see https://go.microsoft.com/fwlink/?linkid=2123754
        [HttpPost]
        public async Task<ActionResult<Mitarbeiter>> PostMitarbeiter(Mitarbeiter mitarbeiter)
        {
            _context.Mitarbeiter.Add(mitarbeiter);
            await _context.SaveChangesAsync();

            return CreatedAtAction("GetMitarbeiter", new { id = mitarbeiter.Id }, mitarbeiter);
        }

        // DELETE: api/Mitarbeiter/5
        [HttpDelete("{id}")]
        public async Task<IActionResult> DeleteMitarbeiter(int id)
        {
            var mitarbeiter = await _context.Mitarbeiter.FindAsync(id);
            if (mitarbeiter == null)
            {
                return NotFound();
            }

            _context.Mitarbeiter.Remove(mitarbeiter);
            await _context.SaveChangesAsync();

            return NoContent();
        }

        private bool MitarbeiterExists(int id)
        {
            return _context.Mitarbeiter.Any(e => e.Id == id);
        }
    }
}
